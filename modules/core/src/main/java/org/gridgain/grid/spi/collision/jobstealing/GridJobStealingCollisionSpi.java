/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.spi.collision.jobstealing;

import org.apache.ignite.cluster.*;
import org.gridgain.grid.compute.*;
import org.gridgain.grid.events.*;
import org.gridgain.grid.kernal.managers.communication.*;
import org.gridgain.grid.kernal.managers.eventstorage.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.resources.*;
import org.gridgain.grid.spi.*;
import org.gridgain.grid.spi.collision.*;
import org.gridgain.grid.spi.failover.jobstealing.*;
import org.gridgain.grid.util.tostring.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jdk8.backport.*;

import java.io.*;
import java.util.*;
import java.util.Map.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.gridgain.grid.events.GridEventType.*;

/**
 * Collision SPI that supports job stealing from over-utilized nodes to
 * under-utilized nodes. This SPI is especially useful if you have
 * some jobs within task complete fast, and others sitting in the waiting
 * queue on slower nodes. In such case, the waiting jobs will be <b>stolen</b>
 * from slower node and moved to the fast under-utilized node.
 * <p>
 * The design and ideas for this SPI are significantly influenced by
 * <a href="http://gee.cs.oswego.edu/dl/papers/fj.pdf">Java Fork/Join Framework</a>
 * authored by Doug Lea and planned for Java 7. {@code GridJobStealingCollisionSpi} took
 * similar concepts and applied them to the grid (as opposed to within VM support planned
 * in Java 7).
 * <p>
 * Quite often grids are deployed across many computers some of which will
 * always be more powerful than others. This SPI helps you avoid jobs being
 * stuck at a slower node, as they will be stolen by a faster node. In the following picture
 * when Node<sub>3</sub> becomes free, it steals Job<sub>13</sub> and Job<sub>23</sub>
 * from Node<sub>1</sub> and Node<sub>2</sub> respectively.
 * <p>
 * <center><img src="http://www.gridgain.com/images/job_stealing_white.gif"></center>
 * <p>
 * <i>
 * Note that this SPI must always be used in conjunction with
 * {@link GridJobStealingFailoverSpi}.
 * Also note that job metrics update should be enabled in order for this SPI
 * to work properly (i.e. {@link org.gridgain.grid.IgniteConfiguration#getMetricsUpdateFrequency()}
 * should be set to {@code 0} or greater value).
 * The responsibility of Job Stealing Failover SPI is to properly route <b>stolen</b>
 * jobs to the nodes that initially requested (<b>stole</b>) these jobs. The
 * SPI maintains a counter of how many times a jobs was stolen and
 * hence traveled to another node. {@code GridJobStealingCollisionSpi}
 * checks this counter and will not allow a job to be stolen if this counter
 * exceeds a certain threshold {@link GridJobStealingCollisionSpi#setMaximumStealingAttempts(int)}.
 * </i>
 * <p>
 * <h1 class="header">Configuration</h1>
 * In order to use this SPI, you should configure your grid instance
 * to use {@code GridJobStealingCollisionSpi} either from Spring XML file or
 * directly. The following configuration parameters are supported:
 * <h2 class="header">Mandatory</h2>
 * This SPI has no mandatory configuration parameters.
 * <h2 class="header">Optional</h2>
 * The following configuration parameters are optional:
 * <ul>
 * <li>
 *      Maximum number of active jobs that will be allowed by this SPI
 *      to execute concurrently (see {@link #setActiveJobsThreshold(int)}).
 * </li>
 * <li>
 *      Maximum number of waiting jobs. Once waiting queue size goes below
 *      this number, this SPI will attempt to steal jobs from over-utilized
 *      nodes by sending <b>"steal"</b> requests (see {@link #setWaitJobsThreshold(int)}).
 * </li>
 * <li>
 *      Steal message expire time. If no response was received from a node
 *      to which <b>steal</b> request was sent, then request will be considered
 *      lost and will be resent, potentially to another node (see {@link #setMessageExpireTime(long)}).
 * </li>
 * <li>
 *      Maximum number of stealing attempts for the job (see {@link #setMaximumStealingAttempts(int)}).
 * </li>
 * <li>
 *      Whether stealing enabled or not (see {@link #setStealingEnabled(boolean)}).
 * </li>
 * <li>
 *     Enables stealing to/from only nodes that have these attributes set
 *     (see {@link #setStealingAttributes(Map)}).
 * </li>
 * </ul>
 * Below is example of configuring this SPI from Java code:
 * <pre name="code" class="java">
 * GridJobStealingCollisionSpi spi = new GridJobStealingCollisionSpi();
 *
 * // Configure number of waiting jobs
 * // in the queue for job stealing.
 * spi.setWaitJobsThreshold(10);
 *
 * // Configure message expire time (in milliseconds).
 * spi.setMessageExpireTime(500);
 *
 * // Configure stealing attempts number.
 * spi.setMaximumStealingAttempts(10);
 *
 * // Configure number of active jobs that are allowed to execute
 * // in parallel. This number should usually be equal to the number
 * // of threads in the pool (default is 100).
 * spi.setActiveJobsThreshold(50);
 *
 * // Enable stealing.
 * spi.setStealingEnabled(true);
 *
 * // Set stealing attribute to steal from/to nodes that have it.
 * spi.setStealingAttributes(Collections.singletonMap("node.segment", "foobar"));
 *
 * GridConfiguration cfg = new GridConfiguration();
 *
 * // Override default Collision SPI.
 * cfg.setCollisionSpi(spi);
 * </pre>
 * Here is an example of how this SPI can be configured from Spring XML configuration:
 * <pre name="code" class="xml">
 * &lt;property name="collisionSpi"&gt;
 *     &lt;bean class="org.gridgain.grid.spi.collision.jobstealing.GridJobStealingCollisionSpi"&gt;
 *         &lt;property name="activeJobsThreshold" value="100"/&gt;
 *         &lt;property name="waitJobsThreshold" value="0"/&gt;
 *         &lt;property name="messageExpireTime" value="1000"/&gt;
 *         &lt;property name="maximumStealingAttempts" value="10"/&gt;
 *         &lt;property name="stealingEnabled" value="true"/&gt;
 *         &lt;property name="stealingAttributes"&gt;
 *             &lt;map&gt;
 *                 &lt;entry key="node.segment" value="foobar"/&gt;
 *             &lt;/map&gt;
 *         &lt;/property&gt;
 *     &lt;/bean&gt;
 * &lt;/property&gt;
 * </pre>
 * <p>
 * <img src="http://www.gridgain.com/images/spring-small.png">
 * <br>
 * For information about Spring framework visit <a href="http://www.springframework.org/">www.springframework.org</a>
 */
@SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
@GridSpiMultipleInstancesSupport(true)
@GridSpiConsistencyChecked(optional = true)
public class GridJobStealingCollisionSpi extends GridSpiAdapter implements GridCollisionSpi,
    GridJobStealingCollisionSpiMBean {
    /** Maximum number of attempts to steal job by another node (default is {@code 5}). */
    public static final int DFLT_MAX_STEALING_ATTEMPTS = 5;

    /**
     * Default number of parallel jobs allowed (value is {@code 95} which is
     * slightly less same as default value of threads in the execution thread pool
     * to allow some extra threads for system processing).
     */
    public static final int DFLT_ACTIVE_JOBS_THRESHOLD = 95;

    /**
     * Default steal message expire time in milliseconds (value is {@code 1000}).
     * Once this time is elapsed and no response for steal message is received,
     * the message is considered lost and another steal message will be generated,
     * potentially to another node.
     */
    public static final long DFLT_MSG_EXPIRE_TIME = 1000;

    /**
     * Default threshold of waiting jobs. If number of waiting jobs exceeds this threshold,
     * then waiting jobs will become available to be stolen (value is {@code 0}).
     */
    public static final int DFLT_WAIT_JOBS_THRESHOLD = 0;

    /** Default start value for job priority (value is {@code 0}). */
    public static final int DFLT_JOB_PRIORITY = 0;

    /** Communication topic. */
    private static final String JOB_STEALING_COMM_TOPIC = "gridgain.collision.job.stealing.topic";

    /** Job context attribute for storing thief node UUID (this attribute is used in job stealing failover SPI). */
    public static final String THIEF_NODE_ATTR = "gridgain.collision.thief.node";

    /** Threshold of maximum jobs on waiting queue. */
    public static final String WAIT_JOBS_THRESHOLD_NODE_ATTR = "gridgain.collision.wait.jobs.threshold";

    /** Threshold of maximum jobs executing concurrently. */
    public static final String ACTIVE_JOBS_THRESHOLD_NODE_ATTR = "gridgain.collision.active.jobs.threshold";

    /**
     * Name of job context attribute containing current stealing attempt count.
     * This count is incremented every time the same job gets stolen for
     * execution.
     *
     * @see GridComputeJobContext
     */
    public static final String STEALING_ATTEMPT_COUNT_ATTR = "gridgain.stealing.attempt.count";

    /** Maximum stealing attempts attribute name. */
    public static final String MAX_STEALING_ATTEMPT_ATTR = "gridgain.stealing.max.attempts";

    /** Stealing request expiration time attribute name. */
    public static final String MSG_EXPIRE_TIME_ATTR = "gridgain.stealing.msg.expire.time";

    /** Stealing priority attribute name. */
    public static final String STEALING_PRIORITY_ATTR = "gridgain.stealing.priority";

    /** Grid logger. */
    @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
    @GridLoggerResource
    private GridLogger log;

    /** Number of jobs that can be executed in parallel. */
    private volatile int activeJobsThreshold = DFLT_ACTIVE_JOBS_THRESHOLD;

    /** Configuration parameter defining waiting job count threshold for stealing to start. */
    @SuppressWarnings("RedundantFieldInitialization")
    private volatile int waitJobsThreshold = DFLT_WAIT_JOBS_THRESHOLD;

    /** Message expire time configuration parameter. */
    private volatile long msgExpireTime = DFLT_MSG_EXPIRE_TIME;

    /** Maximum number of attempts to steal job by another node. */
    private volatile int maxStealingAttempts = DFLT_MAX_STEALING_ATTEMPTS;

    /** Flag indicating whether job stealing is enabled. */
    private volatile boolean isStealingEnabled = true;

    /** Steal attributes. */
    @GridToStringInclude
    private Map<String, ? extends Serializable> stealAttrs;

    /** Number of jobs that were active last time. */
    private volatile int runningNum;

    /** Number of jobs that were waiting for execution last time. */
    private volatile int waitingNum;

    /** Number of currently held jobs. */
    private volatile int heldNum;

    /** Total number of stolen jobs. */
    private final AtomicInteger totalStolenJobsNum = new AtomicInteger();

    /** Map of sent messages. */
    private final ConcurrentMap<UUID, MessageInfo> sndMsgMap = new ConcurrentHashMap8<>();

    /** Map of received messages. */
    private final ConcurrentMap<UUID, MessageInfo> rcvMsgMap = new ConcurrentHashMap8<>();

    /** */
    private final Queue<ClusterNode> nodeQueue = new ConcurrentLinkedDeque8<>();

    /** */
    private GridCollisionExternalListener extLsnr;

    /** Discovery listener. */
    private GridLocalEventListener discoLsnr;

    /** Communication listener. */
    private GridMessageListener msgLsnr;

    /** Number of steal requests. */
    private final AtomicInteger stealReqs = new AtomicInteger();

    /** */
    private Comparator<GridCollisionJobContext> cmp;

    /** {@inheritDoc} */
    @GridSpiConfiguration(optional = true)
    @Override public void setActiveJobsThreshold(int activeJobsThreshold) {
        A.ensure(activeJobsThreshold >= 0, "activeJobsThreshold >= 0");

        this.activeJobsThreshold = activeJobsThreshold;
    }

    /** {@inheritDoc} */
    @Override public int getActiveJobsThreshold() {
        return activeJobsThreshold;
    }

    /** {@inheritDoc} */
    @GridSpiConfiguration(optional = true)
    @Override public void setWaitJobsThreshold(int waitJobsThreshold) {
        A.ensure(waitJobsThreshold >= 0, "waitJobsThreshold >= 0");

        this.waitJobsThreshold = waitJobsThreshold;
    }

    /** {@inheritDoc} */
    @Override public int getWaitJobsThreshold() {
        return waitJobsThreshold;
    }

    /** {@inheritDoc} */
    @GridSpiConfiguration(optional = true)
    @Override public void setMessageExpireTime(long msgExpireTime) {
        A.ensure(msgExpireTime > 0, "messageExpireTime > 0");

        this.msgExpireTime = msgExpireTime;
    }

    /** {@inheritDoc} */
    @Override public long getMessageExpireTime() {
        return msgExpireTime;
    }

    /** {@inheritDoc} */
    @GridSpiConfiguration(optional = true)
    @Override public void setStealingEnabled(boolean isStealingEnabled) {
        this.isStealingEnabled = isStealingEnabled;
    }

    /** {@inheritDoc} */
    @Override public boolean isStealingEnabled() {
        return isStealingEnabled;
    }

    /** {@inheritDoc} */
    @GridSpiConfiguration(optional = true)
    @Override public void setMaximumStealingAttempts(int maxStealingAttempts) {
        A.ensure(maxStealingAttempts > 0, "maxStealingAttempts > 0");

        this.maxStealingAttempts = maxStealingAttempts;
    }

    /** {@inheritDoc} */
    @Override public int getMaximumStealingAttempts() {
        return maxStealingAttempts;
    }

    /**
     * Configuration parameter to enable stealing to/from only nodes that
     * have these attributes set (see {@link org.apache.ignite.cluster.ClusterNode#attribute(String)} and
     * {@link org.gridgain.grid.IgniteConfiguration#getUserAttributes()} methods).
     *
     * @param stealAttrs Node attributes to enable job stealing for.
     */
    @GridSpiConfiguration(optional = true)
    public void setStealingAttributes(Map<String, ? extends Serializable> stealAttrs) {
        this.stealAttrs = stealAttrs;
    }

    /** {@inheritDoc} */
    @Override public Map<String, ? extends Serializable> getStealingAttributes() {
        return stealAttrs;
    }

    /** {@inheritDoc} */
    @Override public int getCurrentRunningJobsNumber() {
        return runningNum;
    }

    /** {@inheritDoc} */
    @Override public int getCurrentHeldJobsNumber() {
        return heldNum;
    }

    /** {@inheritDoc} */
    @Override public int getCurrentWaitJobsNumber() {
        return waitingNum;
    }

    /** {@inheritDoc} */
    @Override public int getCurrentActiveJobsNumber() {
        return runningNum + heldNum;
    }

    /** {@inheritDoc} */
    @Override public int getTotalStolenJobsNumber() {
        return totalStolenJobsNum.get();
    }

    /** {@inheritDoc} */
    @Override public int getCurrentJobsToStealNumber() {
        return stealReqs.get();
    }

    /** {@inheritDoc} */
    @Override public Map<String, Object> getNodeAttributes() throws GridSpiException {
        return F.<String, Object>asMap(
            createSpiAttributeName(WAIT_JOBS_THRESHOLD_NODE_ATTR), waitJobsThreshold,
            createSpiAttributeName(ACTIVE_JOBS_THRESHOLD_NODE_ATTR), activeJobsThreshold,
            createSpiAttributeName(MAX_STEALING_ATTEMPT_ATTR), maxStealingAttempts,
            createSpiAttributeName(MSG_EXPIRE_TIME_ATTR), msgExpireTime);
    }

    /** {@inheritDoc} */
    @Override public void spiStart(String gridName) throws GridSpiException {
        assertParameter(activeJobsThreshold >= 0, "activeJobsThreshold >= 0");
        assertParameter(waitJobsThreshold >= 0, "waitJobsThreshold >= 0");
        assertParameter(msgExpireTime > 0, "messageExpireTime > 0");
        assertParameter(maxStealingAttempts > 0, "maxStealingAttempts > 0");

        // Start SPI start stopwatch.
        startStopwatch();

        // Ack parameters.
        if (log.isDebugEnabled()) {
            log.debug(configInfo("activeJobsThreshold", activeJobsThreshold));
            log.debug(configInfo("waitJobsThreshold", waitJobsThreshold));
            log.debug(configInfo("messageExpireTime", msgExpireTime));
            log.debug(configInfo("maxStealingAttempts", maxStealingAttempts));
        }

        registerMBean(gridName, this, GridJobStealingCollisionSpiMBean.class);

        // Ack start.
        if (log.isDebugEnabled())
            log.debug(startInfo());
    }

    /** {@inheritDoc} */
    @Override public void spiStop() throws GridSpiException {
        unregisterMBean();

        // Ack ok stop.
        if (log.isDebugEnabled())
            log.debug(stopInfo());
    }

    /** {@inheritDoc} */
    @Override public void setExternalCollisionListener(GridCollisionExternalListener extLsnr) {
        this.extLsnr = extLsnr;
    }

    /** {@inheritDoc} */
    @Override protected void onContextInitialized0(GridSpiContext spiCtx) throws GridSpiException {
        spiCtx.addLocalEventListener(
            discoLsnr = new GridLocalEventListener() {
                @SuppressWarnings("fallthrough")
                @Override public void onEvent(GridEvent evt) {
                    assert evt instanceof GridDiscoveryEvent;

                    GridDiscoveryEvent discoEvt = (GridDiscoveryEvent)evt;

                    UUID evtNodeId = discoEvt.eventNode().id();

                    switch (discoEvt.type()) {
                        case EVT_NODE_JOINED:
                            ClusterNode node = getSpiContext().node(evtNodeId);

                            if (node != null) {
                                nodeQueue.offer(node);

                                sndMsgMap.putIfAbsent(node.id(), new MessageInfo());
                                rcvMsgMap.putIfAbsent(node.id(), new MessageInfo());
                            }

                            break;

                        case EVT_NODE_LEFT:
                        case EVT_NODE_FAILED:
                            Iterator<ClusterNode> iter = nodeQueue.iterator();

                            while (iter.hasNext()) {
                                ClusterNode nextNode = iter.next();

                                if (nextNode.id().equals(evtNodeId))
                                    iter.remove();
                            }

                            sndMsgMap.remove(evtNodeId);
                            rcvMsgMap.remove(evtNodeId);

                            break;

                        default:
                            assert false : "Unexpected event: " + evt;
                    }
                }
            },
            EVT_NODE_FAILED,
            EVT_NODE_JOINED,
            EVT_NODE_LEFT
        );

        Collection<ClusterNode> rmtNodes = spiCtx.remoteNodes();

        for (ClusterNode node : rmtNodes) {
            UUID id = node.id();

            if (spiCtx.node(id) != null) {
                sndMsgMap.putIfAbsent(id, new MessageInfo());
                rcvMsgMap.putIfAbsent(id, new MessageInfo());

                // Check if node has concurrently left.
                if (spiCtx.node(id) == null) {
                    sndMsgMap.remove(id);
                    rcvMsgMap.remove(id);
                }
            }
        }

        nodeQueue.addAll(rmtNodes);

        Iterator<ClusterNode> iter = nodeQueue.iterator();

        while (iter.hasNext()) {
            ClusterNode nextNode = iter.next();

            if (spiCtx.node(nextNode.id()) == null)
                iter.remove();
        }

        spiCtx.addMessageListener(
            msgLsnr = new GridMessageListener() {
                @Override public void onMessage(UUID nodeId, Object msg) {
                    MessageInfo info = rcvMsgMap.get(nodeId);

                    if (info == null) {
                        if (log.isDebugEnabled())
                            log.debug("Ignoring message steal request as discovery event has not yet been received " +
                                "for node: " + nodeId);

                        return;
                    }

                    int stealReqs0;

                    synchronized (info) {
                        GridJobStealingRequest req = (GridJobStealingRequest)msg;

                        // Increment total number of steal requests.
                        // Note that it is critical to increment total
                        // number of steal requests before resetting message info.
                        stealReqs0 = stealReqs.addAndGet(req.delta() - info.jobsToSteal());

                        info.reset(req.delta());
                    }

                    if (log.isDebugEnabled())
                        log.debug("Received steal request [nodeId=" + nodeId + ", msg=" + msg +
                            ", stealReqs=" + stealReqs0 + ']');

                    GridCollisionExternalListener tmp = extLsnr;

                    // Let grid know that collisions should be resolved.
                    if (tmp != null)
                        tmp.onExternalCollision();
                }
            },
            JOB_STEALING_COMM_TOPIC);
    }

    /** {@inheritDoc} */
    @Override public void onContextDestroyed0() {
        if (discoLsnr != null)
            getSpiContext().removeLocalEventListener(discoLsnr);

        if (msgLsnr != null)
            getSpiContext().removeMessageListener(msgLsnr, JOB_STEALING_COMM_TOPIC);
    }

    /** {@inheritDoc} */
    @Override public void onCollision(GridCollisionContext ctx) {
        assert ctx != null;

        Collection<GridCollisionJobContext> activeJobs = ctx.activeJobs();
        Collection<GridCollisionJobContext> waitJobs = ctx.waitingJobs();

        heldNum = ctx.heldJobs().size();

        // Check if there are any jobs to activate or reject.
        int rejected = checkBusy(waitJobs, activeJobs);

        totalStolenJobsNum.addAndGet(rejected);

        // No point of stealing jobs if some jobs were rejected.
        if (rejected > 0) {
            if (log.isDebugEnabled())
                log.debug("Total count of rejected jobs: " + rejected);

            return;
        }

        if (isStealingEnabled)
            // Check if there are jobs to steal.
            checkIdle(waitJobs, activeJobs);
    }

    /**
     * Check if node is busy and activate/reject proper number of jobs.
     *
     * @param waitJobs Waiting jobs.
     * @param activeJobs Active jobs.
     * @return Number of rejected jobs.
     */
    private int checkBusy(Collection<GridCollisionJobContext> waitJobs,
        Collection<GridCollisionJobContext> activeJobs) {

        int activeSize = activeJobs.size();
        int waitSize = waitJobs.size();

        waitingNum = waitJobs.size();
        runningNum = activeSize;

        GridSpiContext ctx = getSpiContext();

        int activated = 0;
        int rejected = 0;

        Collection<GridCollisionJobContext> waitPriJobs = sortJobs(waitJobs, waitSize);

        int activeJobsThreshold0 = activeJobsThreshold;
        int waitJobsThreshold0 = waitJobsThreshold;

        for (GridCollisionJobContext waitCtx : waitPriJobs) {
            if (activeJobs.size() < activeJobsThreshold0) {
                activated++;

                // If job was activated/cancelled by another thread, then
                // this method is no-op.
                // We also need to make sure that job is not being rejected by another thread.
                synchronized (waitCtx.getJobContext()) {
                    waitCtx.activate();
                }
            }
            else if (stealReqs.get() > 0) {
                if (waitCtx.getJob().getClass().isAnnotationPresent(GridJobStealingDisabled.class))
                    continue;

                // Collision count attribute.
                Integer stealingCnt = waitCtx.getJobContext().getAttribute(STEALING_ATTEMPT_COUNT_ATTR);

                // Check that maximum stealing attempt threshold
                // has not been exceeded.
                if (stealingCnt != null) {
                    // If job exceeded failover threshold, skip it.
                    if (stealingCnt >= maxStealingAttempts) {
                        if (log.isDebugEnabled())
                            log.debug("Waiting job exceeded stealing attempts and won't be rejected " +
                                "(will try other jobs on waiting list): " + waitCtx);

                        continue;
                    }
                }
                else
                    stealingCnt = 0;

                // Check if allowed to reject job.
                int jobsToReject = waitPriJobs.size() - activated - rejected - waitJobsThreshold0;

                if (log.isDebugEnabled())
                    log.debug("Jobs to reject count [jobsToReject=" + jobsToReject + ", waitCtx=" + waitCtx + ']');

                if (jobsToReject <= 0)
                    break;

                Integer pri = waitCtx.getJobContext().getAttribute(STEALING_PRIORITY_ATTR);

                if (pri == null)
                    pri = DFLT_JOB_PRIORITY;

                // If we have an excess of waiting jobs, reject as many as there are
                // requested to be stolen. Note, that we use lose total steal request
                // counter to prevent excessive iteration over nodes under load.
                for (Iterator<Entry<UUID, MessageInfo>> iter = rcvMsgMap.entrySet().iterator();
                     iter.hasNext() && stealReqs.get() > 0;) {
                    Entry<UUID, MessageInfo> entry = iter.next();

                    UUID nodeId = entry.getKey();

                    // Node has left topology.
                    if (ctx.node(nodeId) == null) {
                        iter.remove();

                        continue;
                    }

                    MessageInfo info = entry.getValue();

                    synchronized (info) {
                        int jobsAsked = info.jobsToSteal();

                        assert jobsAsked >= 0;

                        // Skip nodes that have not asked for jobs to steal.
                        if (jobsAsked == 0)
                            // Move to next node.
                            continue;

                        // If message is expired, ignore it.
                        if (info.expired()) {
                            // Subtract expired messages.
                            stealReqs.addAndGet(-info.jobsToSteal());

                            info.reset(0);

                            continue;
                        }

                        // Check that waiting job has thief node in topology.
                        boolean found = false;

                        for (UUID id : waitCtx.getTaskSession().getTopology()) {
                            if (id.equals(nodeId)) {
                                found = true;

                                break;
                            }
                        }

                        if (!found) {
                            if (log.isDebugEnabled())
                                log.debug("Thief node does not belong to task topology [thief=" + nodeId +
                                    ", task=" + waitCtx.getTaskSession() + ']');

                            continue;
                        }

                        if (stealReqs.get() <= 0)
                            break;

                        // Need to make sure that job is not being
                        // rejected by another thread.
                        synchronized (waitCtx.getJobContext()) {
                            boolean cancel = waitCtx.getJobContext().getAttribute(THIEF_NODE_ATTR) == null;

                            if (cancel) {
                                // Mark job as stolen.
                                waitCtx.getJobContext().setAttribute(THIEF_NODE_ATTR, nodeId);
                                waitCtx.getJobContext().setAttribute(STEALING_ATTEMPT_COUNT_ATTR, stealingCnt + 1);
                                waitCtx.getJobContext().setAttribute(STEALING_PRIORITY_ATTR, pri + 1);

                                if (log.isDebugEnabled())
                                    log.debug("Will try to reject job due to steal request [ctx=" + waitCtx +
                                        ", thief=" + nodeId + ']');

                                int i = stealReqs.decrementAndGet();

                                if (i >= 0 && waitCtx.cancel()) {
                                    rejected++;

                                    info.reset(jobsAsked - 1);

                                    if (log.isDebugEnabled())
                                        log.debug("Rejected job due to steal request [ctx=" + waitCtx +
                                            ", nodeId=" + nodeId + ']');
                                }
                                else {
                                    if (log.isDebugEnabled())
                                        log.debug("Failed to reject job [i=" + i + ']');

                                    waitCtx.getJobContext().setAttribute(THIEF_NODE_ATTR, null);
                                    waitCtx.getJobContext().setAttribute(STEALING_ATTEMPT_COUNT_ATTR, stealingCnt);
                                    waitCtx.getJobContext().setAttribute(STEALING_PRIORITY_ATTR, pri);

                                    stealReqs.incrementAndGet();
                                }
                            }
                        }

                        // Move to next job.
                        break;
                    }
                }
            }
            else
                // No more jobs to steal or activate.
                break;
        }

        return rejected;
    }

    /**
     * Sort jobs by priority from high to lowest value.
     *
     * @param waitJobs Waiting jobs.
     * @param waitSize Snapshot size.
     * @return Sorted waiting jobs by priority.
     */
    private Collection<GridCollisionJobContext> sortJobs(Collection<GridCollisionJobContext> waitJobs, int waitSize) {
        List<GridCollisionJobContext> passiveList = new ArrayList<>(waitJobs.size());

        int i = 0;

        for (GridCollisionJobContext waitJob : waitJobs) {
            passiveList.add(waitJob);

            if (i++ == waitSize)
                break;
        }

        Collections.sort(passiveList, comparator());

        return passiveList;
    }

    /**
     * @return Comparator.
     */
    private Comparator<GridCollisionJobContext> comparator() {
        if (cmp == null) {
            cmp = new Comparator<GridCollisionJobContext>() {
                @Override public int compare(GridCollisionJobContext o1, GridCollisionJobContext o2) {
                    int p1 = getJobPriority(o1.getJobContext());
                    int p2 = getJobPriority(o2.getJobContext());

                    return p1 < p2 ? 1 : p1 == p2 ? 0 : -1;
                }
            };
        }

        return cmp;
    }

    /**
     * Gets job priority from task context. If job has no priority default one will be used.
     *
     * @param ctx Job context.
     * @return Job priority.
     */
    private int getJobPriority(GridComputeJobContext ctx) {
        assert ctx != null;

        Integer p;

        try {
            p = ctx.getAttribute(STEALING_PRIORITY_ATTR);
        }
        catch (ClassCastException e) {
            U.error(log, "Type of job context priority attribute '" + STEALING_PRIORITY_ATTR +
                "' is not java.lang.Integer (will use default priority) [type=" +
                ctx.getAttribute(STEALING_PRIORITY_ATTR).getClass() + ", dfltPriority=" + DFLT_JOB_PRIORITY + ']', e);

            p = DFLT_JOB_PRIORITY;
        }

        if (p == null)
            p = DFLT_JOB_PRIORITY;

        return p;
    }

    /**
     * Check if the node is idle and steal as many jobs from other nodes
     * as possible.
     *
     * @param waitJobs Waiting jobs.
     * @param activeJobs Active jobs.
     */
    private void checkIdle(Collection<GridCollisionJobContext> waitJobs,
        Collection<GridCollisionJobContext> activeJobs) {
        // Check for overflow.
        int max = waitJobsThreshold + activeJobsThreshold;

        if (max < 0)
            max = Integer.MAX_VALUE;

        int jobsToSteal = max - (waitJobs.size() + activeJobs.size());

        if (log.isDebugEnabled())
            log.debug("Total number of jobs to be stolen: " + jobsToSteal);

        if (jobsToSteal > 0) {
            int jobsLeft = jobsToSteal;

            ClusterNode next;

            int nodeCnt = getSpiContext().remoteNodes().size();

            int idx = 0;

            while (jobsLeft > 0 && idx++ < nodeCnt && (next = nodeQueue.poll()) != null) {
                if (getSpiContext().node(next.id()) == null)
                    continue;

                // Remote node does not have attributes - do not steal from it.
                if (!F.isEmpty(stealAttrs) &&
                    (next.attributes() == null || !U.containsAll(next.attributes(), stealAttrs))) {
                    if (log.isDebugEnabled())
                        log.debug("Skip node as it does not have all attributes: " + next.id());

                    continue;
                }

                int delta = 0;

                try {
                    MessageInfo msgInfo = sndMsgMap.get(next.id());

                    if (msgInfo == null) {
                        if (log.isDebugEnabled())
                            log.debug("Failed to find message info for node: " + next.id());

                        // Node left topology or SPI has not received message for it.
                        continue;
                    }

                    Integer waitThreshold =
                        next.attribute(createSpiAttributeName(WAIT_JOBS_THRESHOLD_NODE_ATTR));

                    if (waitThreshold == null) {
                        U.error(log, "Remote node is not configured with GridJobStealingCollisionSpi and " +
                            "jobs will not be stolen from it (you must stop it and update its configuration to use " +
                            "GridJobStealingCollisionSpi): " + next);

                        continue;
                    }

                    delta = next.metrics().getCurrentWaitingJobs() - waitThreshold;

                    if (log.isDebugEnabled())
                        log.debug("Maximum number of jobs to steal from node [jobsToSteal=" + delta + ", node=" +
                            next.id() + ']');

                    // Nothing to steal from this node.
                    if (delta <= 0)
                        continue;

                    synchronized (msgInfo) {
                        if (!msgInfo.expired() && msgInfo.jobsToSteal() > 0) {
                            // Count messages being waited for as present.
                            jobsLeft -= msgInfo.jobsToSteal();

                            continue;
                        }

                        if (jobsLeft < delta)
                            delta = jobsLeft;

                        jobsLeft -= delta;

                        msgInfo.reset(delta);
                    }

                    // Send request to remote node to steal jobs.
                    // Message is a plain integer represented by 'delta'.
                    getSpiContext().send(next, new GridJobStealingRequest(delta), JOB_STEALING_COMM_TOPIC);
                }
                catch (GridSpiException e) {
                    U.error(log, "Failed to send job stealing message to node: " + next, e);

                    // Rollback.
                    jobsLeft += delta;
                }
                finally {
                    // If node is alive, add back to the end of the queue.
                    if (getSpiContext().node(next.id()) != null)
                        nodeQueue.offer(next);
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override protected List<String> getConsistentAttributeNames() {
        List<String> attrs = new ArrayList<>(2);

        attrs.add(createSpiAttributeName(MAX_STEALING_ATTEMPT_ATTR));
        attrs.add(createSpiAttributeName(MSG_EXPIRE_TIME_ATTR));

        return attrs;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridJobStealingCollisionSpi.class, this);
    }

    /**
     *
     */
    private class MessageInfo {
        /** */
        private int jobsToSteal;

        /** */
        private long ts = U.currentTimeMillis();

        /**
         * @return Job to steal.
         */
        int jobsToSteal() {
            assert Thread.holdsLock(this);

            return jobsToSteal;
        }

        /**
         * @return {@code True} if message is expired.
         */
        boolean expired() {
            assert Thread.holdsLock(this);

            return jobsToSteal > 0 && U.currentTimeMillis() - ts >= msgExpireTime;
        }

        /**
         * @param jobsToSteal Jobs to steal.
         */
        void reset(int jobsToSteal) {
            assert Thread.holdsLock(this);

            this.jobsToSteal = jobsToSteal;

            ts = U.currentTimeMillis();
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(MessageInfo.class, this);
        }
    }

}
