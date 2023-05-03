package shopping.cart
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.grpc.GrpcServiceException
import akka.util.Timeout
import io.grpc.Status
import org.slf4j.LoggerFactory
import shopping.cart.proto.{AddItemRequest, Cart, Item}

import java.util.concurrent.TimeoutException
import scala.concurrent.Future

class ShoppingCartServiceImpl(system: ActorSystem[_]) extends proto.ShoppingCartService {

  private val logger = LoggerFactory.getLogger(getClass)

  implicit private val timeout: Timeout = Timeout.create(
    system.settings.config.getDuration("shopping-cart-service.ask-timeout")
  )

  private val sharding = ClusterSharding(system)

  override def addItem(in: AddItemRequest): Future[Cart] = {
    logger.info("addItem {} to cart {}", in.itemId, in.cartId)
    val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, in.cartId)
    val reply = entityRef.askWithStatus(ShoppingCart.AddItem(in.itemId, in.quantity, _))
    val response = reply.map(toProtoCart)(system.executionContext)
    convertError(response)
  }

  private def toProtoCart(cart: ShoppingCart.Summary): Cart =
    Cart(items = cart.items.map { case (itemId, quantity) =>
      Item(itemId = itemId, quantity = quantity)
    }.toSeq)

  private def convertError[T](response: Future[T]): Future[T] = {
    response.recoverWith {
      case _: TimeoutException =>
        Future.failed(
          new GrpcServiceException(
            Status.UNAVAILABLE.withDescription("Operation timed out")))
      case exc =>
        Future.failed(
          new GrpcServiceException(
            Status.INVALID_ARGUMENT.withDescription(exc.getMessage)))
    }(system.executionContext)
  }
}
