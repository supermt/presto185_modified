/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.server.remotetask;

import com.facebook.presto.OutputBuffers;
import com.facebook.presto.ScheduledSplit;
import com.facebook.presto.Session;
import com.facebook.presto.TaskSource;
import com.facebook.presto.execution.FutureStateChange;
import com.facebook.presto.execution.NodeTaskMap.PartitionedSplitCountTracker;
import com.facebook.presto.execution.RemoteTask;
import com.facebook.presto.execution.StateMachine.StateChangeListener;
import com.facebook.presto.execution.TaskId;
import com.facebook.presto.execution.TaskInfo;
import com.facebook.presto.execution.TaskState;
import com.facebook.presto.execution.TaskStatus;
import com.facebook.presto.execution.buffer.BufferInfo;
import com.facebook.presto.execution.buffer.PageBufferInfo;
import com.facebook.presto.metadata.Split;
import com.facebook.presto.operator.TaskStats;
import com.facebook.presto.server.TaskUpdateRequest;
import com.facebook.presto.sql.planner.PlanFragment;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.tree.BooleanLiteral;
import com.facebook.presto.sql.tree.Expression;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.concurrent.SetThreadName;
import io.airlift.http.client.FullJsonResponseHandler.JsonResponse;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.HttpUriBuilder;
import io.airlift.http.client.Request;
import io.airlift.json.JsonCodec;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import org.joda.time.DateTime;

import javax.annotation.concurrent.GuardedBy;

import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.facebook.presto.execution.TaskInfo.createInitialTask;
import static com.facebook.presto.execution.TaskState.ABORTED;
import static com.facebook.presto.execution.TaskState.FAILED;
import static com.facebook.presto.execution.TaskStatus.failWith;
import static com.facebook.presto.server.remotetask.RequestErrorTracker.logError;
import static com.facebook.presto.util.Failures.toFailure;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static io.airlift.http.client.FullJsonResponseHandler.createFullJsonResponseHandler;
import static io.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static io.airlift.http.client.JsonBodyGenerator.jsonBodyGenerator;
import static io.airlift.http.client.Request.Builder.prepareDelete;
import static io.airlift.http.client.Request.Builder.preparePost;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public final class HttpRemoteTask
        implements RemoteTask
{
    private static final Logger log = Logger.get(HttpRemoteTask.class);
    private static final Duration MAX_CLEANUP_RETRY_TIME = new Duration(2, TimeUnit.MINUTES);
    private static final int MIN_RETRIES = 3;

    private final TaskId taskId;

    private final Session session;
    private final String nodeId;
    private final PlanFragment planFragment;

    private final AtomicLong nextSplitId = new AtomicLong();

    private final RemoteTaskStats stats;
    private final TaskInfoFetcher taskInfoFetcher;
    private final ContinuousTaskStatusFetcher taskStatusFetcher;
    @GuardedBy("this")
    private final SetMultimap<PlanNodeId, ScheduledSplit> pendingSplits = HashMultimap.create();
    @GuardedBy("this")
    private final Set<PlanNodeId> noMoreSplits = new HashSet<>();
    @GuardedBy("this")
    private final AtomicReference<OutputBuffers> outputBuffers = new AtomicReference<>();
    private final FutureStateChange<?> whenSplitQueueHasSpace = new FutureStateChange<>();
    private final boolean summarizeTaskInfo;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final Executor executor;
    private final ScheduledExecutorService errorScheduledExecutor;
    private final JsonCodec<TaskInfo> taskInfoCodec;
    private final JsonCodec<TaskUpdateRequest> taskUpdateRequestCodec;
    private final RequestErrorTracker updateErrorTracker;
    private final AtomicBoolean needsUpdate = new AtomicBoolean(true);
    private final AtomicBoolean sendPlan = new AtomicBoolean(true);
    private final PartitionedSplitCountTracker partitionedSplitCountTracker;
    private final AtomicBoolean aborting = new AtomicBoolean(false);
    @GuardedBy("this")
    private Future<?> currentRequest;
    @GuardedBy("this")
    private long currentRequestStartNanos;
    @GuardedBy("this")
    private volatile int pendingSourceSplitCount;
    @GuardedBy("this")
    private boolean splitQueueHasSpace = true;
    @GuardedBy("this")
    private OptionalInt whenSplitQueueHasSpaceThreshold = OptionalInt.empty();

    public HttpRemoteTask(Session session,
            TaskId taskId,
            String nodeId,
            URI location,
            PlanFragment planFragment,
            Multimap<PlanNodeId, Split> initialSplits,
            OutputBuffers outputBuffers,
            HttpClient httpClient,
            Executor executor,
            ScheduledExecutorService updateScheduledExecutor,
            ScheduledExecutorService errorScheduledExecutor,
            Duration minErrorDuration,
            Duration maxErrorDuration,
            Duration taskStatusRefreshMaxWait,
            Duration taskInfoUpdateInterval,
            boolean summarizeTaskInfo,
            JsonCodec<TaskStatus> taskStatusCodec,
            JsonCodec<TaskInfo> taskInfoCodec,
            JsonCodec<TaskUpdateRequest> taskUpdateRequestCodec,
            PartitionedSplitCountTracker partitionedSplitCountTracker,
            RemoteTaskStats stats)
    {
        requireNonNull(session, "session is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(nodeId, "nodeId is null");
        requireNonNull(location, "location is null");
        requireNonNull(planFragment, "planFragment is null");
        requireNonNull(outputBuffers, "outputBuffers is null");
        requireNonNull(httpClient, "httpClient is null");
        requireNonNull(executor, "executor is null");
        requireNonNull(taskStatusCodec, "taskStatusCodec is null");
        requireNonNull(taskInfoCodec, "taskInfoCodec is null");
        requireNonNull(taskUpdateRequestCodec, "taskUpdateRequestCodec is null");
        requireNonNull(partitionedSplitCountTracker, "partitionedSplitCountTracker is null");
        requireNonNull(stats, "stats is null");

        try (SetThreadName ignored = new SetThreadName("HttpRemoteTask-%s", taskId)) {
            this.taskId = taskId;
            this.session = session;
            this.nodeId = nodeId;
            this.planFragment = planFragment;
            this.outputBuffers.set(outputBuffers);
            this.httpClient = httpClient;
            this.executor = executor;
            this.errorScheduledExecutor = errorScheduledExecutor;
            this.summarizeTaskInfo = summarizeTaskInfo;
            this.taskInfoCodec = taskInfoCodec;
            this.taskUpdateRequestCodec = taskUpdateRequestCodec;
            this.updateErrorTracker = new RequestErrorTracker(taskId, location, minErrorDuration, maxErrorDuration, errorScheduledExecutor, "updating task");
            this.partitionedSplitCountTracker = requireNonNull(partitionedSplitCountTracker, "partitionedSplitCountTracker is null");
            this.stats = stats;

            PlanNode rootNode = planFragment.getRoot();
            // add by YJH
            pendingConditions(rootNode, initialSplits);

            for (Entry<PlanNodeId, Split> entry : requireNonNull(initialSplits, "initialSplits is null").entries()) {
                ScheduledSplit scheduledSplit = new ScheduledSplit(nextSplitId.getAndIncrement(), entry.getKey(), entry.getValue());
                pendingSplits.put(entry.getKey(), scheduledSplit);
            }
            pendingSourceSplitCount = planFragment.getPartitionedSources().stream()
                    .filter(initialSplits::containsKey)
                    .mapToInt(partitionedSource -> initialSplits.get(partitionedSource).size())
                    .sum();

            List<BufferInfo> bufferStates = outputBuffers.getBuffers()
                    .keySet().stream()
                    .map(outputId -> new BufferInfo(outputId, false, 0, 0, PageBufferInfo.empty()))
                    .collect(toImmutableList());

            TaskInfo initialTask = createInitialTask(taskId, location, nodeId, bufferStates, new TaskStats(DateTime.now(), null));

            this.taskStatusFetcher = new ContinuousTaskStatusFetcher(
                    this::failTask,
                    initialTask.getTaskStatus(),
                    taskStatusRefreshMaxWait,
                    taskStatusCodec,
                    executor,
                    httpClient,
                    minErrorDuration,
                    maxErrorDuration,
                    errorScheduledExecutor,
                    stats);

            this.taskInfoFetcher = new TaskInfoFetcher(
                    this::failTask,
                    initialTask,
                    httpClient,
                    taskInfoUpdateInterval,
                    taskInfoCodec,
                    minErrorDuration,
                    maxErrorDuration,
                    summarizeTaskInfo,
                    executor,
                    updateScheduledExecutor,
                    errorScheduledExecutor,
                    stats);

            taskStatusFetcher.addStateChangeListener(newStatus -> {
                TaskState state = newStatus.getState();
                if (state.isDone()) {
                    cleanUpTask();
                }
                else {
                    partitionedSplitCountTracker.setPartitionedSplitCount(getPartitionedSplitCount());
                    updateSplitQueueSpace();
                }
            });

            long timeout = minErrorDuration.toMillis() / MIN_RETRIES;
            this.requestTimeout = new Duration(timeout + taskStatusRefreshMaxWait.toMillis(), MILLISECONDS);
            partitionedSplitCountTracker.setPartitionedSplitCount(getPartitionedSplitCount());
            updateSplitQueueSpace();
        }
    }

    // Add by YJH
    public void pendingConditions(PlanNode rootNode, Multimap<PlanNodeId, Split> splitMap)
    {
        if (splitMap.size() == 0) {
            return;
        }

        Queue<PlanNode> queue = new LinkedList<>();

        List<TableScanNode> conditionNodes = new LinkedList<>();

        queue.addAll(rootNode.getSources());

        PlanNode temp = null;

        while (queue.size() != 0) {
            temp = queue.poll();
            for (PlanNode source : temp.getSources()) {
                if (source instanceof TableScanNode) {
                    conditionNodes.add((TableScanNode) source);
                    continue;
                }
                queue.offer(source);
            }
        }

        for (TableScanNode node : conditionNodes) {
            Expression condition = node.getOriginalConstraint();
            final String tableFullName = node.getTable().getConnectorHandle().toString().replaceAll(":", "-");
            final String attachString = condition.toString() + "@" + tableFullName;
            if (!(condition instanceof BooleanLiteral)) {
                Collection<Split> target = splitMap.get(node.getId());
                target.forEach(split -> {
                    if (split.getConnectorSplit().withParam()) {
                        split.getConnectorSplit().setParam(attachString);
                    }
                });
            }
        }
    }

    @Override
    public TaskId getTaskId()
    {
        return taskId;
    }

    @Override
    public String getNodeId()
    {
        return nodeId;
    }

    @Override
    public TaskInfo getTaskInfo()
    {
        return taskInfoFetcher.getTaskInfo();
    }

    @Override
    public TaskStatus getTaskStatus()
    {
        return taskStatusFetcher.getTaskStatus();
    }

    @Override
    public void start()
    {
        try (SetThreadName ignored = new SetThreadName("HttpRemoteTask-%s", taskId)) {
            // to start we just need to trigger an update
            scheduleUpdate();

            taskStatusFetcher.start();
            taskInfoFetcher.start();
        }
    }

    @Override
    public synchronized void addSplits(Multimap<PlanNodeId, Split> splitsBySource)
    {
        requireNonNull(splitsBySource, "splitsBySource is null");

        // only add pending split if not done
        if (getTaskStatus().getState().isDone()) {
            return;
        }

        for (Entry<PlanNodeId, Collection<Split>> entry : splitsBySource.asMap().entrySet()) {
            PlanNodeId sourceId = entry.getKey();
            Collection<Split> splits = entry.getValue();

            checkState(!noMoreSplits.contains(sourceId), "noMoreSplits has already been set for %s", sourceId);
            int added = 0;
            for (Split split : splits) {
                if (pendingSplits.put(sourceId, new ScheduledSplit(nextSplitId.getAndIncrement(), sourceId, split))) {
                    added++;
                }
            }
            if (planFragment.isPartitionedSources(sourceId)) {
                pendingSourceSplitCount += added;
                partitionedSplitCountTracker.setPartitionedSplitCount(getPartitionedSplitCount());
            }
            needsUpdate.set(true);
        }
        updateSplitQueueSpace();

        scheduleUpdate();
    }

    @Override
    public synchronized void noMoreSplits(PlanNodeId sourceId)
    {
        if (noMoreSplits.add(sourceId)) {
            needsUpdate.set(true);
            scheduleUpdate();
        }
    }

    @Override
    public synchronized void setOutputBuffers(OutputBuffers newOutputBuffers)
    {
        if (getTaskStatus().getState().isDone()) {
            return;
        }

        if (newOutputBuffers.getVersion() > outputBuffers.get().getVersion()) {
            outputBuffers.set(newOutputBuffers);
            needsUpdate.set(true);
            scheduleUpdate();
        }
    }

    @Override
    public int getPartitionedSplitCount()
    {
        TaskStatus taskStatus = getTaskStatus();
        if (taskStatus.getState().isDone()) {
            return 0;
        }
        return getPendingSourceSplitCount() + taskStatus.getQueuedPartitionedDrivers() + taskStatus.getRunningPartitionedDrivers();
    }

    @Override
    public int getQueuedPartitionedSplitCount()
    {
        TaskStatus taskStatus = getTaskStatus();
        if (taskStatus.getState().isDone()) {
            return 0;
        }
        return getPendingSourceSplitCount() + taskStatus.getQueuedPartitionedDrivers();
    }

    @SuppressWarnings("FieldAccessNotGuarded")
    private int getPendingSourceSplitCount()
    {
        return pendingSourceSplitCount;
    }

    @Override
    public void addStateChangeListener(StateChangeListener<TaskStatus> stateChangeListener)
    {
        try (SetThreadName ignored = new SetThreadName("HttpRemoteTask-%s", taskId)) {
            taskStatusFetcher.addStateChangeListener(stateChangeListener);
        }
    }

    @Override
    public synchronized ListenableFuture<?> whenSplitQueueHasSpace(int threshold)
    {
        if (whenSplitQueueHasSpaceThreshold.isPresent()) {
            checkArgument(threshold == whenSplitQueueHasSpaceThreshold.getAsInt(), "Multiple split queue space notification thresholds not supported");
        }
        else {
            whenSplitQueueHasSpaceThreshold = OptionalInt.of(threshold);
            updateSplitQueueSpace();
        }
        if (splitQueueHasSpace) {
            return immediateFuture(null);
        }
        return whenSplitQueueHasSpace.createNewListener();
    }

    private synchronized void updateSplitQueueSpace()
    {
        if (!whenSplitQueueHasSpaceThreshold.isPresent()) {
            return;
        }
        splitQueueHasSpace = getQueuedPartitionedSplitCount() < whenSplitQueueHasSpaceThreshold.getAsInt();
        if (splitQueueHasSpace) {
            whenSplitQueueHasSpace.complete(null, executor);
        }
    }

    private synchronized void processTaskUpdate(TaskInfo newValue, List<TaskSource> sources)
    {
        updateTaskInfo(newValue);

        // remove acknowledged splits, which frees memory
        for (TaskSource source : sources) {
            PlanNodeId planNodeId = source.getPlanNodeId();
            int removed = 0;
            for (ScheduledSplit split : source.getSplits()) {
                if (pendingSplits.remove(planNodeId, split)) {
                    removed++;
                }
            }
            if (planFragment.isPartitionedSources(planNodeId)) {
                pendingSourceSplitCount -= removed;
            }
        }
        updateSplitQueueSpace();

        partitionedSplitCountTracker.setPartitionedSplitCount(getPartitionedSplitCount());
    }

    private void updateTaskInfo(TaskInfo taskInfo)
    {
        taskStatusFetcher.updateTaskStatus(taskInfo.getTaskStatus());
        taskInfoFetcher.updateTaskInfo(taskInfo);
    }

    private void scheduleUpdate()
    {
        executor.execute(this::sendUpdate);
    }

    private synchronized void sendUpdate()
    {
        TaskStatus taskStatus = getTaskStatus();
        // don't update if the task hasn't been started yet or if it is already finished
        if (!needsUpdate.get() || taskStatus.getState().isDone()) {
            return;
        }

        // if we have an old request outstanding, cancel it
        if (currentRequest != null && Duration.nanosSince(currentRequestStartNanos).compareTo(requestTimeout) >= 0) {
            needsUpdate.set(true);
            currentRequest.cancel(true);
            currentRequest = null;
            currentRequestStartNanos = 0;
        }

        // if there is a request already running, wait for it to complete
        if (this.currentRequest != null && !this.currentRequest.isDone()) {
            return;
        }

        // if throttled due to error, asynchronously wait for timeout and try again
        ListenableFuture<?> errorRateLimit = updateErrorTracker.acquireRequestPermit();
        if (!errorRateLimit.isDone()) {
            errorRateLimit.addListener(this::sendUpdate, executor);
            return;
        }

        List<TaskSource> sources = getSources();

        Optional<PlanFragment> fragment = Optional.empty();
        if (sendPlan.get()) {
            fragment = Optional.of(planFragment);
        }
        TaskUpdateRequest updateRequest = new TaskUpdateRequest(session.toSessionRepresentation(),
                fragment,
                sources,
                outputBuffers.get());

        HttpUriBuilder uriBuilder = getHttpUriBuilder(taskStatus);
        Request request = preparePost()
                .setUri(uriBuilder.build())
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .setBodyGenerator(jsonBodyGenerator(taskUpdateRequestCodec, updateRequest))
                .build();

        updateErrorTracker.startRequest();

        ListenableFuture<JsonResponse<TaskInfo>> future = httpClient.executeAsync(request, createFullJsonResponseHandler(taskInfoCodec));
        currentRequest = future;
        currentRequestStartNanos = System.nanoTime();

        // The needsUpdate flag needs to be set to false BEFORE adding the Future callback since callback might change the flag value
        // and does so without grabbing the instance lock.
        needsUpdate.set(false);

        Futures.addCallback(future, new SimpleHttpResponseHandler<>(new UpdateResponseHandler(sources), request.getUri(), stats), executor);
    }

    private synchronized List<TaskSource> getSources()
    {
        return Stream.concat(planFragment.getPartitionedSourceNodes().stream(), planFragment.getRemoteSourceNodes().stream())
                .filter(Objects::nonNull)
                .map(PlanNode::getId)
                .map(this::getSource)
                .filter(Objects::nonNull)
                .collect(toImmutableList());
    }

    private synchronized TaskSource getSource(PlanNodeId planNodeId)
    {
        Set<ScheduledSplit> splits = pendingSplits.get(planNodeId);
        boolean noMoreSplits = this.noMoreSplits.contains(planNodeId);
        TaskSource element = null;
        if (!splits.isEmpty() || noMoreSplits) {
            element = new TaskSource(planNodeId, splits, noMoreSplits);
        }
        return element;
    }

    @Override
    public synchronized void cancel()
    {
        try (SetThreadName ignored = new SetThreadName("HttpRemoteTask-%s", taskId)) {
            TaskStatus taskStatus = getTaskStatus();
            if (taskStatus.getState().isDone()) {
                return;
            }

            // send cancel to task and ignore response
            HttpUriBuilder uriBuilder = getHttpUriBuilder(taskStatus).addParameter("abort", "false");
            Request request = prepareDelete()
                    .setUri(uriBuilder.build())
                    .build();
            scheduleAsyncCleanupRequest(new Backoff(MAX_CLEANUP_RETRY_TIME, MAX_CLEANUP_RETRY_TIME), request, "cancel");
        }
    }

    private synchronized void cleanUpTask()
    {
        checkState(getTaskStatus().getState().isDone(), "attempt to clean up a task that is not done yet");

        // clear pending splits to free memory
        pendingSplits.clear();
        pendingSourceSplitCount = 0;
        partitionedSplitCountTracker.setPartitionedSplitCount(getPartitionedSplitCount());
        splitQueueHasSpace = true;
        whenSplitQueueHasSpace.complete(null, executor);

        // cancel pending request
        if (currentRequest != null) {
            currentRequest.cancel(true);
            currentRequest = null;
            currentRequestStartNanos = 0;
        }

        taskStatusFetcher.stop();

        // The remote task is likely to get a delete from the PageBufferClient first.
        // We send an additional delete anyway to get the final TaskInfo
        HttpUriBuilder uriBuilder = getHttpUriBuilder(getTaskStatus());
        Request request = prepareDelete()
                .setUri(uriBuilder.build())
                .build();

        scheduleAsyncCleanupRequest(new Backoff(MAX_CLEANUP_RETRY_TIME, MAX_CLEANUP_RETRY_TIME), request, "cleanup");
    }

    @Override
    public synchronized void abort()
    {
        if (getTaskStatus().getState().isDone()) {
            return;
        }

        abort(failWith(getTaskStatus(), ABORTED, ImmutableList.of()));
    }

    private synchronized void abort(TaskStatus status)
    {
        checkState(status.getState().isDone(), "cannot abort task with an incomplete status");

        try (SetThreadName ignored = new SetThreadName("HttpRemoteTask-%s", taskId)) {
            taskStatusFetcher.updateTaskStatus(status);

            // send abort to task
            HttpUriBuilder uriBuilder = getHttpUriBuilder(getTaskStatus());
            Request request = prepareDelete()
                    .setUri(uriBuilder.build())
                    .build();
            scheduleAsyncCleanupRequest(new Backoff(MAX_CLEANUP_RETRY_TIME, MAX_CLEANUP_RETRY_TIME), request, "abort");
        }
    }

    private void scheduleAsyncCleanupRequest(Backoff cleanupBackoff, Request request, String action)
    {
        if (!aborting.compareAndSet(false, true)) {
            // Do not initiate another round of cleanup requests if one had been initiated.
            // Otherwise, we can get into an asynchronous recursion here. For example, when aborting a task after REMOTE_TASK_MISMATCH.
            return;
        }
        doScheduleAsyncCleanupRequest(cleanupBackoff, request, action);
    }

    private void doScheduleAsyncCleanupRequest(Backoff cleanupBackoff, Request request, String action)
    {
        Futures.addCallback(httpClient.executeAsync(request, createFullJsonResponseHandler(taskInfoCodec)), new FutureCallback<JsonResponse<TaskInfo>>()
        {
            @Override
            public void onSuccess(JsonResponse<TaskInfo> result)
            {
                try {
                    updateTaskInfo(result.getValue());
                }
                finally {
                    if (!getTaskInfo().getTaskStatus().getState().isDone()) {
                        cleanUpLocally();
                    }
                }
            }

            @Override
            public void onFailure(Throwable t)
            {
                if (t instanceof RejectedExecutionException) {
                    // TODO: we should only give up retrying when the client has been shutdown
                    logError(t, "Unable to %s task at %s. Got RejectedExecutionException.", action, request.getUri());
                    cleanUpLocally();
                    return;
                }

                // record failure
                if (cleanupBackoff.failure()) {
                    logError(t, "Unable to %s task at %s. Back off depleted.", action, request.getUri());
                    cleanUpLocally();
                    return;
                }

                // reschedule
                long delayNanos = cleanupBackoff.getBackoffDelayNanos();
                if (delayNanos == 0) {
                    doScheduleAsyncCleanupRequest(cleanupBackoff, request, action);
                }
                else {
                    errorScheduledExecutor.schedule(() -> doScheduleAsyncCleanupRequest(cleanupBackoff, request, action), delayNanos, NANOSECONDS);
                }
            }

            private void cleanUpLocally()
            {
                // Update the taskInfo with the new taskStatus.

                // Generally, we send a cleanup request to the worker, and update the TaskInfo on
                // the coordinator based on what we fetched from the worker. If we somehow cannot
                // get the cleanup request to the worker, the TaskInfo that we fetch for the worker
                // likely will not say the task is done however many times we try. In this case,
                // we have to set the local query info directly so that we stop trying to fetch
                // updated TaskInfo from the worker. This way, the task on the worker eventually
                // expires due to lack of activity.

                // This is required because the query state machine depends on TaskInfo (instead of task status)
                // to transition its own state.
                // TODO: Update the query state machine and stage state machine to depend on TaskStatus instead

                // Since this TaskInfo is updated in the client the "complete" flag will not be set,
                // indicating that the stats may not reflect the final stats on the worker.
                updateTaskInfo(getTaskInfo().withTaskStatus(getTaskStatus()));
            }
        }, executor);
    }

    /**
     * Move the task directly to the failed state if there was a failure in this task
     */
    private void failTask(Throwable cause)
    {
        TaskStatus taskStatus = getTaskStatus();
        if (!taskStatus.getState().isDone()) {
            log.debug(cause, "Remote task %s failed with %s", taskStatus.getSelf(), cause);
        }

        abort(failWith(getTaskStatus(), FAILED, ImmutableList.of(toFailure(cause))));
    }

    private HttpUriBuilder getHttpUriBuilder(TaskStatus taskStatus)
    {
        HttpUriBuilder uriBuilder = uriBuilderFrom(taskStatus.getSelf());
        if (summarizeTaskInfo) {
            uriBuilder.addParameter("summarize");
        }
        return uriBuilder;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .addValue(getTaskInfo())
                .toString();
    }

    private class UpdateResponseHandler
            implements SimpleHttpResponseCallback<TaskInfo>
    {
        private final List<TaskSource> sources;

        private UpdateResponseHandler(List<TaskSource> sources)
        {
            this.sources = ImmutableList.copyOf(requireNonNull(sources, "sources is null"));
        }

        @Override
        public void success(TaskInfo value)
        {
            try (SetThreadName ignored = new SetThreadName("UpdateResponseHandler-%s", taskId)) {
                try {
                    long currentRequestStartNanos;
                    synchronized (HttpRemoteTask.this) {
                        currentRequest = null;
                        sendPlan.set(value.isNeedsPlan());
                        currentRequestStartNanos = HttpRemoteTask.this.currentRequestStartNanos;
                    }
                    updateStats(currentRequestStartNanos);
                    processTaskUpdate(value, sources);
                    updateErrorTracker.requestSucceeded();
                }
                finally {
                    sendUpdate();
                }
            }
        }

        @Override
        public void failed(Throwable cause)
        {
            try (SetThreadName ignored = new SetThreadName("UpdateResponseHandler-%s", taskId)) {
                try {
                    long currentRequestStartNanos;
                    synchronized (HttpRemoteTask.this) {
                        currentRequest = null;
                        currentRequestStartNanos = HttpRemoteTask.this.currentRequestStartNanos;
                    }
                    updateStats(currentRequestStartNanos);

                    // on failure assume we need to update again
                    needsUpdate.set(true);

                    // if task not already done, record error
                    TaskStatus taskStatus = getTaskStatus();
                    if (!taskStatus.getState().isDone()) {
                        updateErrorTracker.requestFailed(cause);
                    }
                }
                catch (Error e) {
                    failTask(e);
                    throw e;
                }
                catch (RuntimeException e) {
                    failTask(e);
                }
                finally {
                    sendUpdate();
                }
            }
        }

        @Override
        public void fatal(Throwable cause)
        {
            try (SetThreadName ignored = new SetThreadName("UpdateResponseHandler-%s", taskId)) {
                failTask(cause);
            }
        }

        private void updateStats(long currentRequestStartNanos)
        {
            Duration requestRoundTrip = Duration.nanosSince(currentRequestStartNanos);
            stats.updateRoundTripMillis(requestRoundTrip.toMillis());
        }
    }
}
