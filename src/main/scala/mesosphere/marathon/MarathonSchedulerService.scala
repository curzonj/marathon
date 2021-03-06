package mesosphere.marathon

import org.apache.mesos.Protos.{FrameworkInfo, FrameworkID}
import org.apache.mesos.MesosSchedulerDriver
import java.util.logging.Logger
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.MarathonStore
import com.google.common.util.concurrent.AbstractIdleService
import javax.inject.{Named, Inject}
import java.util.{TimerTask, Timer}
import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.Duration
import java.util.concurrent.atomic.AtomicBoolean
import com.twitter.common.base.ExceptionalCommand
import com.twitter.common.zookeeper.Group.JoinException
import scala.Option
import com.twitter.common.zookeeper.Candidate
import com.twitter.common.zookeeper.Candidate.Leader
import scala.util.Random

/**
 * Wrapper class for the scheduler
 *
 * @author Tobi Knaup
 */
class MarathonSchedulerService @Inject()(
    @Named(ModuleNames.NAMED_CANDIDATE) candidate: Option[Candidate],
    config: MarathonConfiguration,
    @Named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean,
    store: MarathonStore[AppDefinition],
    scheduler: MarathonScheduler)
  extends AbstractIdleService with Leader {

  // TODO use a thread pool here
  import ExecutionContext.Implicits.global

  // Time to wait before trying to balance app tasks after driver starts
  val balanceWait = Duration(10, "seconds")

  val log = Logger.getLogger(getClass.getName)

  val frameworkName = "marathon-" + Main.VERSION

  val frameworkId = FrameworkID.newBuilder.setValue(frameworkName).build

  val frameworkInfo = FrameworkInfo.newBuilder()
    .setName(frameworkName)
    .setId(frameworkId)
    .setFailoverTimeout(Main.getConfiguration.mesosFailoverTimeout())
    .setUser("") // Let Mesos assign the user
    .build()

  val driver = new MesosSchedulerDriver(scheduler, frameworkInfo, config.mesosMaster.get.get)


  def startApp(app: AppDefinition) {
    // Backwards compatibility
    if (app.ports == Nil) {
      val port = newAppPort(app)
      app.ports = Seq(port)
      log.info(s"Assigned port $port to app '${app.id}'")
    }

    scheduler.startApp(driver, app)
  }

  def stopApp(app: AppDefinition) {
    scheduler.stopApp(driver, app)
  }

  def scaleApp(app: AppDefinition) {
    scheduler.scaleApp(driver, app)
  }

  def listApps(): Seq[AppDefinition] = {
    // TODO method is expensive, it's n+1 trips to ZK. Cache this?
    val names = Await.result(store.names(), store.defaultWait)
    val futures = names.map(name => store.fetch(name))
    val futureServices = Future.sequence(futures)
    Await.result(futureServices, store.defaultWait).map(_.get).toSeq
  }

  //Begin Service interface
  def startUp() {
    log.info("Starting up")
    if (leader.get) {
      runDriver()
    } else {
      offerLeaderShip()
    }
  }

  def shutDown() {
    log.info("Shutting down")
    stopDriver()
  }

  def runDriver() {
    log.info("Running driver")
    scheduleTaskBalancing()
    driver.run()
  }

  def stopDriver() {
    log.info("Stopping driver")
    driver.stop()
  }

  def isLeader = {
    leader.get() || getLeader.isEmpty
  }

  def getLeader: Option[String] = {
    if (candidate.nonEmpty && candidate.get.getLeaderData.isPresent) {
      return Some(new String(candidate.get.getLeaderData.get))
    }
    None
  }
  //End Service interface

  //Begin Leader interface, which is required for CandidateImpl.
  def onDefeated() {
    log.info("Defeated")
    leader.set(false)
    stopDriver()

    // Don't offer leadership if we're shutting down
    if (isRunning) {
      offerLeaderShip()
    }
  }

  def onElected(abdicate: ExceptionalCommand[JoinException]) {
    log.info("Elected")
    leader.set(true)
    runDriver()
  }
  //End Leader interface

  private def scheduleTaskBalancing() {
    val timer = new Timer()
    val task = new TimerTask {
      def run() {
        scheduler.balanceTasks(driver)
      }
    }
    timer.schedule(task, balanceWait.toMillis)
  }

  private def offerLeaderShip() {
    if (candidate.nonEmpty) {
      log.info("Offering leadership.")
      candidate.get.offerLeadership(this)
    }
  }

  private def newAppPort(app: AppDefinition): Int = {
    // TODO this is pretty expensive, find a better way
    val assignedPorts = listApps().map(_.ports).flatten
    var port = 0
    do {
      port = config.localPortMin() + Random.nextInt(config.localPortMax() - config.localPortMin())
    } while (assignedPorts.contains(port))
    port
  }
}
