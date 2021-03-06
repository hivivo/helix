package org.apache.helix.task;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.helix.AccessOption;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.PropertyKey;
import org.apache.helix.ZNRecord;
import org.apache.helix.controller.stages.ClusterDataCache;
import org.apache.helix.controller.stages.CurrentStateOutput;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.Message;
import org.apache.helix.model.Partition;
import org.apache.helix.model.ResourceAssignment;
import org.apache.helix.task.assigner.ThreadCountBasedTaskAssigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JobDispatcher extends AbstractTaskDispatcher {
  private static final Logger LOG = LoggerFactory.getLogger(JobDispatcher.class);
  private ClusterDataCache _clusterDataCache;

  public void updateCache(ClusterDataCache cache) {
    _clusterDataCache = cache;
  }

  public ResourceAssignment processJobStatusUpdateandAssignment(String jobName,
      CurrentStateOutput currStateOutput, IdealState taskIs) {
    // Fetch job configuration
    JobConfig jobCfg = _clusterDataCache.getJobConfig(jobName);
    if (jobCfg == null) {
      LOG.error("Job configuration is NULL for " + jobName);
      return buildEmptyAssignment(jobName, currStateOutput);
    }
    String workflowResource = jobCfg.getWorkflow();

    // Fetch workflow configuration and context
    WorkflowConfig workflowCfg = _clusterDataCache.getWorkflowConfig(workflowResource);
    if (workflowCfg == null) {
      LOG.error("Workflow configuration is NULL for " + jobName);
      return buildEmptyAssignment(jobName, currStateOutput);
    }

    WorkflowContext workflowCtx = _clusterDataCache.getWorkflowContext(workflowResource);
    if (workflowCtx == null) {
      LOG.error("Workflow context is NULL for " + jobName);
      return buildEmptyAssignment(jobName, currStateOutput);
    }

    TargetState targetState = workflowCfg.getTargetState();
    if (targetState != TargetState.START && targetState != TargetState.STOP) {
      LOG.info("Target state is " + targetState.name() + " for workflow " + workflowResource
          + ".Stop scheduling job " + jobName);
      return buildEmptyAssignment(jobName, currStateOutput);
    }

    // Stop current run of the job if workflow or job is already in final state (failed or
    // completed)
    TaskState workflowState = workflowCtx.getWorkflowState();
    TaskState jobState = workflowCtx.getJobState(jobName);
    // The job is already in a final state (completed/failed).
    if (workflowState == TaskState.FAILED || workflowState == TaskState.COMPLETED
        || jobState == TaskState.FAILED || jobState == TaskState.COMPLETED) {
      LOG.info(String.format(
          "Workflow %s or job %s is already failed or completed, workflow state (%s), job state (%s), clean up job IS.",
          workflowResource, jobName, workflowState, jobState));
      TaskUtil.cleanupJobIdealStateExtView(_manager.getHelixDataAccessor(), jobName);
      _rebalanceScheduler.removeScheduledRebalance(jobName);
      return buildEmptyAssignment(jobName, currStateOutput);
    }

    if (!isWorkflowReadyForSchedule(workflowCfg)) {
      LOG.info("Job is not ready to be run since workflow is not ready " + jobName);
      return buildEmptyAssignment(jobName, currStateOutput);
    }

    if (!TaskUtil.isJobStarted(jobName, workflowCtx) && !isJobReadyToSchedule(jobName, workflowCfg,
        workflowCtx, TaskUtil.getInCompleteJobCount(workflowCfg, workflowCtx),
        _clusterDataCache.getJobConfigMap(), _clusterDataCache.getTaskDataCache())) {
      LOG.info("Job is not ready to run " + jobName);
      return buildEmptyAssignment(jobName, currStateOutput);
    }

    // Fetch any existing context information from the property store.
    JobContext jobCtx = _clusterDataCache.getJobContext(jobName);
    if (jobCtx == null) {
      jobCtx = new JobContext(new ZNRecord(TaskUtil.TASK_CONTEXT_KW));
      jobCtx.setStartTime(System.currentTimeMillis());
      jobCtx.setName(jobName);
      workflowCtx.setJobState(jobName, TaskState.IN_PROGRESS);
    }

    if (!TaskState.TIMED_OUT.equals(workflowCtx.getJobState(jobName))) {
      scheduleRebalanceForTimeout(jobCfg.getJobId(), jobCtx.getStartTime(), jobCfg.getTimeout());
    }

    // Grab the old assignment, or an empty one if it doesn't exist
    ResourceAssignment prevAssignment = getPrevResourceAssignment(jobName);
    if (prevAssignment == null) {
      prevAssignment = new ResourceAssignment(jobName);
    }

    // Will contain the list of partitions that must be explicitly dropped from the ideal state that
    // is stored in zk.
    // Fetch the previous resource assignment from the property store. This is required because of
    // HELIX-230.
    Set<String> liveInstances =
        jobCfg.getInstanceGroupTag() == null ? _clusterDataCache.getEnabledLiveInstances()
            : _clusterDataCache.getEnabledLiveInstancesWithTag(jobCfg.getInstanceGroupTag());

    if (liveInstances.isEmpty()) {
      LOG.error("No available instance found for job!");
    }

    TargetState jobTgtState = workflowCfg.getTargetState();
    jobState = workflowCtx.getJobState(jobName);
    workflowState = workflowCtx.getWorkflowState();

    if (jobState == TaskState.IN_PROGRESS && (isTimeout(jobCtx.getStartTime(), jobCfg.getTimeout())
        || TaskState.TIMED_OUT.equals(workflowState))) {
      jobState = TaskState.TIMING_OUT;
      workflowCtx.setJobState(jobName, TaskState.TIMING_OUT);
    } else if (jobState != TaskState.TIMING_OUT && jobState != TaskState.FAILING) {
      // TIMING_OUT/FAILING/ABORTING job can't be stopped, because all tasks are being aborted
      // Update running status in workflow context
      if (jobTgtState == TargetState.STOP) {
        if (TaskUtil.checkJobStopped(jobCtx)) {
          workflowCtx.setJobState(jobName, TaskState.STOPPED);
        } else {
          workflowCtx.setJobState(jobName, TaskState.STOPPING);
        }
        // Workflow has been stopped if all in progress jobs are stopped
        if (isWorkflowStopped(workflowCtx, workflowCfg)) {
          workflowCtx.setWorkflowState(TaskState.STOPPED);
        } else {
          workflowCtx.setWorkflowState(TaskState.STOPPING);
        }
      } else {
        workflowCtx.setJobState(jobName, TaskState.IN_PROGRESS);
        // Workflow is in progress if any task is in progress
        workflowCtx.setWorkflowState(TaskState.IN_PROGRESS);
      }
    }

    Set<Integer> partitionsToDrop = new TreeSet<>();
    ResourceAssignment newAssignment =
        computeResourceMapping(jobName, workflowCfg, jobCfg, jobState, jobTgtState, prevAssignment,
            liveInstances, currStateOutput, workflowCtx, jobCtx, partitionsToDrop, _clusterDataCache);

    HelixDataAccessor accessor = _manager.getHelixDataAccessor();
    PropertyKey propertyKey = accessor.keyBuilder().idealStates(jobName);

    // TODO: will be removed when we are trying to get rid of IdealState
    taskIs = _clusterDataCache.getIdealState(jobName);
    if (!partitionsToDrop.isEmpty() && taskIs != null) {
      for (Integer pId : partitionsToDrop) {
        taskIs.getRecord().getMapFields().remove(pName(jobName, pId));
      }
      accessor.setProperty(propertyKey, taskIs);
    }

    // Update Workflow and Job context in data cache and ZK.
    _clusterDataCache.updateJobContext(jobName, jobCtx);
    _clusterDataCache.updateWorkflowContext(workflowResource, workflowCtx);

    setPrevResourceAssignment(jobName, newAssignment);

    LOG.debug("Job " + jobName + " new assignment "
        + Arrays.toString(newAssignment.getMappedPartitions().toArray()));
    return newAssignment;
  }

  private ResourceAssignment computeResourceMapping(String jobResource,
      WorkflowConfig workflowConfig, JobConfig jobCfg, TaskState jobState, TargetState jobTgtState,
      ResourceAssignment prevTaskToInstanceStateAssignment, Collection<String> liveInstances,
      CurrentStateOutput currStateOutput, WorkflowContext workflowCtx, JobContext jobCtx,
      Set<Integer> partitionsToDropFromIs, ClusterDataCache cache) {

    // Used to keep track of tasks that have already been assigned to instances.
    Set<Integer> assignedPartitions = new HashSet<>();

    // Used to keep track of tasks that have failed, but whose failure is acceptable
    Set<Integer> skippedPartitions = new HashSet<>();

    // Keeps a mapping of (partition) -> (instance, state)
    Map<Integer, PartitionAssignment> paMap = new TreeMap<>();

    Set<String> excludedInstances =
        getExcludedInstances(jobResource, workflowConfig, workflowCtx, cache);

    // Process all the current assignments of tasks.
    TaskAssignmentCalculator taskAssignmentCal = getAssignmentCalculator(jobCfg, cache);
    Set<Integer> allPartitions = taskAssignmentCal.getAllTaskPartitions(jobCfg, jobCtx,
        workflowConfig, workflowCtx, cache.getIdealStates());

    if (allPartitions == null || allPartitions.isEmpty()) {
      // Empty target partitions, mark the job as FAILED.
      String failureMsg =
          "Empty task partition mapping for job " + jobResource + ", marked the job as FAILED!";
      LOG.info(failureMsg);
      jobCtx.setInfo(failureMsg);
      failJob(jobResource, workflowCtx, jobCtx, workflowConfig, cache.getJobConfigMap(), cache);
      markAllPartitionsError(jobCtx, TaskPartitionState.ERROR, false);
      return new ResourceAssignment(jobResource);
    }

    Map<String, SortedSet<Integer>> prevInstanceToTaskAssignments =
        getPrevInstanceToTaskAssignments(liveInstances, prevTaskToInstanceStateAssignment,
            allPartitions);
    long currentTime = System.currentTimeMillis();

    if (LOG.isDebugEnabled()) {
      LOG.debug("All partitions: " + allPartitions + " taskAssignment: "
          + prevInstanceToTaskAssignments + " excludedInstances: " + excludedInstances);
    }

    // Release resource for tasks in terminal state
    updatePreviousAssignedTasksStatus(prevInstanceToTaskAssignments, excludedInstances, jobResource,
        currStateOutput, jobCtx, jobCfg, prevTaskToInstanceStateAssignment, jobState,
        assignedPartitions, partitionsToDropFromIs, paMap, jobTgtState, skippedPartitions, cache);

    addGiveupPartitions(skippedPartitions, jobCtx, allPartitions, jobCfg);

    if (jobState == TaskState.IN_PROGRESS && skippedPartitions.size() > jobCfg.getFailureThreshold()
        || (jobCfg.getTargetResource() != null
        && cache.getIdealState(jobCfg.getTargetResource()) != null
        && !cache.getIdealState(jobCfg.getTargetResource()).isEnabled())) {
      if (isJobFinished(jobCtx, jobResource, currStateOutput)) {
        failJob(jobResource, workflowCtx, jobCtx, workflowConfig, cache.getJobConfigMap(), cache);
        return buildEmptyAssignment(jobResource, currStateOutput);
      }
      workflowCtx.setJobState(jobResource, TaskState.FAILING);
      // Drop all assigned but not given-up tasks
      for (int pId : jobCtx.getPartitionSet()) {
        String instance = jobCtx.getAssignedParticipant(pId);
        if (jobCtx.getPartitionState(pId) != null && !isTaskGivenup(jobCtx, jobCfg, pId)) {
          paMap.put(pId, new PartitionAssignment(instance, TaskPartitionState.TASK_ABORTED.name()));
        }
        Partition partition = new Partition(pName(jobResource, pId));
        Message pendingMessage = currStateOutput.getPendingMessage(jobResource, partition, instance);
        // While job is failing, if the task is pending on INIT->RUNNING, set it back to INIT,
        // so that Helix will cancel the transition.
        if (jobCtx.getPartitionState(pId) == TaskPartitionState.INIT && pendingMessage != null) {
          paMap.put(pId, new PartitionAssignment(instance, TaskPartitionState.INIT.name()));
        }
      }

      return toResourceAssignment(jobResource, paMap);
    }

    if (jobState == TaskState.FAILING && isJobFinished(jobCtx, jobResource, currStateOutput)) {
      failJob(jobResource, workflowCtx, jobCtx, workflowConfig, cache.getJobConfigMap(), cache);
      return buildEmptyAssignment(jobResource, currStateOutput);
    }

    if (isJobComplete(jobCtx, allPartitions, jobCfg)) {
      markJobComplete(jobResource, jobCtx, workflowConfig, workflowCtx, cache.getJobConfigMap(),
          cache);
      _clusterStatusMonitor.updateJobCounters(jobCfg, TaskState.COMPLETED,
          jobCtx.getFinishTime() - jobCtx.getStartTime());
      _rebalanceScheduler.removeScheduledRebalance(jobResource);
      TaskUtil.cleanupJobIdealStateExtView(_manager.getHelixDataAccessor(), jobResource);
      return buildEmptyAssignment(jobResource, currStateOutput);
    }

    // If job is being timed out and no task is running (for whatever reason), idealState can be
    // deleted and all tasks
    // can be dropped(note that Helix doesn't track whether the drop is success or not).
    if (jobState == TaskState.TIMING_OUT && isJobFinished(jobCtx, jobResource, currStateOutput)) {
      handleJobTimeout(jobCtx, workflowCtx, jobResource, jobCfg);
      return buildEmptyAssignment(jobResource, currStateOutput);
    }

    // For delayed tasks, trigger a rebalance event for the closest upcoming ready time
    scheduleForNextTask(jobResource, jobCtx, currentTime);

    // Make additional task assignments if needed.
    if (jobState != TaskState.TIMING_OUT && jobState != TaskState.TIMED_OUT
        && jobTgtState == TargetState.START) {
      handleAdditionalTaskAssignment(prevInstanceToTaskAssignments, excludedInstances, jobResource,
          currStateOutput, jobCtx, jobCfg, workflowConfig, workflowCtx, cache,
          prevTaskToInstanceStateAssignment, assignedPartitions, paMap, skippedPartitions,
          taskAssignmentCal, allPartitions, currentTime, liveInstances);
    }

    return toResourceAssignment(jobResource, paMap);
  }

  private ResourceAssignment toResourceAssignment(String jobResource,
      Map<Integer, PartitionAssignment> paMap) {
    // Construct a ResourceAssignment object from the map of partition assignments.
    ResourceAssignment ra = new ResourceAssignment(jobResource);
    for (Map.Entry<Integer, PartitionAssignment> e : paMap.entrySet()) {
      PartitionAssignment pa = e.getValue();
      ra.addReplicaMap(new Partition(pName(jobResource, e.getKey())),
          ImmutableMap.of(pa._instance, pa._state));
    }
    return ra;
  }

  private boolean isJobFinished(JobContext jobContext, String jobResource,
      CurrentStateOutput currentStateOutput) {
    for (int pId : jobContext.getPartitionSet()) {
      TaskPartitionState state = jobContext.getPartitionState(pId);
      Partition partition = new Partition(pName(jobResource, pId));
      String instance = jobContext.getAssignedParticipant(pId);
      Message pendingMessage = currentStateOutput.getPendingMessage(jobResource, partition,
          instance);
      // If state is INIT but is pending INIT->RUNNING, it's not yet safe to say the job finished
      if (state == TaskPartitionState.RUNNING
          || (state == TaskPartitionState.INIT && pendingMessage != null)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Get the last task assignment for a given job
   * @param resourceName the name of the job
   * @return {@link ResourceAssignment} instance, or null if no assignment is available
   */
  private ResourceAssignment getPrevResourceAssignment(String resourceName) {
    ZNRecord r = _manager.getHelixPropertyStore().get(
        Joiner.on("/").join(TaskConstants.REBALANCER_CONTEXT_ROOT, resourceName, TaskConstants.PREV_RA_NODE),
        null, AccessOption.PERSISTENT);
    return r != null ? new ResourceAssignment(r) : null;
  }

  /**
   * Set the last task assignment for a given job
   * @param resourceName the name of the job
   * @param ra {@link ResourceAssignment} containing the task assignment
   */
  private void setPrevResourceAssignment(String resourceName, ResourceAssignment ra) {
    _manager.getHelixPropertyStore().set(
        Joiner.on("/").join(TaskConstants.REBALANCER_CONTEXT_ROOT, resourceName, TaskConstants.PREV_RA_NODE),
        ra.getRecord(), AccessOption.PERSISTENT);
  }

  /**
   * Checks if the job has completed. Look at states of all tasks of the job, there're 3 kind:
   * completed, given up, not given up. The job is completed if all tasks are completed or given up,
   * and the number of given up tasks is within job failure threshold.
   */
  private static boolean isJobComplete(JobContext ctx, Set<Integer> allPartitions, JobConfig cfg) {
    int numOfGivenUpTasks = 0;
    // Iterate through all tasks, if any one indicates the job has not completed, return false.
    for (Integer pId : allPartitions) {
      TaskPartitionState state = ctx.getPartitionState(pId);
      if (state != TaskPartitionState.COMPLETED) {
        if (!isTaskGivenup(ctx, cfg, pId)) {
          return false;
        }
        // If the task is given up, there's still chance the job has completed because of job
        // failure threshold.
        numOfGivenUpTasks++;
      }
    }
    return numOfGivenUpTasks <= cfg.getFailureThreshold();
  }

  /**
   * @param liveInstances
   * @param prevAssignment task partition -> (instance -> state)
   * @param allTaskPartitions all task partitionIds
   * @return instance -> partitionIds from previous assignment, if the instance is still live
   */
  private static Map<String, SortedSet<Integer>> getPrevInstanceToTaskAssignments(
      Iterable<String> liveInstances, ResourceAssignment prevAssignment,
      Set<Integer> allTaskPartitions) {
    Map<String, SortedSet<Integer>> result = new HashMap<String, SortedSet<Integer>>();
    for (String instance : liveInstances) {
      result.put(instance, new TreeSet<Integer>());
    }

    for (Partition partition : prevAssignment.getMappedPartitions()) {
      int pId = TaskUtil.getPartitionId(partition.getPartitionName());
      if (allTaskPartitions.contains(pId)) {
        Map<String, String> replicaMap = prevAssignment.getReplicaMap(partition);
        for (String instance : replicaMap.keySet()) {
          SortedSet<Integer> pList = result.get(instance);
          if (pList != null) {
            pList.add(pId);
          }
        }
      }
    }
    return result;
  }

  /**
   * If the job is a targeted job, use fixedTaskAssignmentCalculator. Otherwise, use
   * threadCountBasedTaskAssignmentCalculator. Both calculators support quota-based scheduling.
   * @param jobConfig
   * @param cache
   * @return
   */
  private TaskAssignmentCalculator getAssignmentCalculator(JobConfig jobConfig,
      ClusterDataCache cache) {
    AssignableInstanceManager assignableInstanceManager = cache.getAssignableInstanceManager();
    if (TaskUtil.isGenericTaskJob(jobConfig)) {
      return new ThreadCountBasedTaskAssignmentCalculator(new ThreadCountBasedTaskAssigner(),
          assignableInstanceManager);
    }
    return new FixedTargetTaskAssignmentCalculator(assignableInstanceManager);
  }
}
