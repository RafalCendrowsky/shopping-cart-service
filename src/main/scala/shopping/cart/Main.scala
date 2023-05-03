package shopping.cart

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorSystem
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import org.slf4j.{Logger, LoggerFactory}
import shopping.cart.repository.{ItemPopularityRepositoryImpl, ScalikeJdbcSetup}

import scala.util.control.NonFatal

object Main {

  val logger: Logger = LoggerFactory.getLogger("shopping.cart.Main")

  def main(args: Array[String]): Unit = {
    val system = ActorSystem[Nothing](Behaviors.empty, "ShoppingCartService")
    try {
      init(system)
    } catch {
      case NonFatal(e) =>
        logger.error("Terminating due to initialization failure.", e)
        system.terminate()
    }
  }

  def init(system: ActorSystem[_]): Unit = {
    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    val itemPopularityRepository = new ItemPopularityRepositoryImpl()
    val grpcInterface = system.settings.config.getString("shopping-cart-service.grpc.interface")
    val grpcPort = system.settings.config.getInt("shopping-cart-service.grpc.port")
    val grpcService = new ShoppingCartServiceImpl(system, itemPopularityRepository)

    ScalikeJdbcSetup.init(system)
    ItemPopularityProjection.init(system, itemPopularityRepository)
    ShoppingCartServer.start(grpcInterface, grpcPort, system, grpcService)
    ShoppingCart.init(system)
    PublishEventsProjection.init(system)
  }

}
