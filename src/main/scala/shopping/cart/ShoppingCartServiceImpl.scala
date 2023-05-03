package shopping.cart
import akka.actor.typed.{ActorSystem, DispatcherSelector}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.grpc.GrpcServiceException
import akka.util.Timeout
import io.grpc.Status
import org.slf4j.LoggerFactory
import shopping.cart.proto.{AddItemRequest, AdjustItemQuantityRequest, Cart, CheckoutRequest, GetCartRequest, GetItemPopularityRequest, GetItemPopularityResponse, Item, RemoveItemRequest}
import shopping.cart.repository.{ItemPopularityRepository, ScalikeJdbcSession}

import java.util.concurrent.TimeoutException
import scala.concurrent.Future

class ShoppingCartServiceImpl(
  system: ActorSystem[_],
  itemPopularityRepository: ItemPopularityRepository
) extends proto.ShoppingCartService {

  private val logger = LoggerFactory.getLogger(getClass)

  private val blockingJdbcExecutor =
    system.dispatchers.lookup(
      DispatcherSelector.fromConfig("akka.projection.jdbc.blocking-jdbc-dispatcher")
    )

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

  override def removeItem(in: RemoveItemRequest): Future[Cart] = {
    logger.info("removeItem {} from cart {}", in.itemId, in.cartId)
    val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, in.cartId)
    val reply = entityRef.askWithStatus(ShoppingCart.RemoveItem(in.itemId, _))
    val response = reply.map(toProtoCart)(system.executionContext)
    convertError(response)
  }

  override def adjustItemQuantity(in: AdjustItemQuantityRequest): Future[Cart] = {
    logger.info("adjustItemQuantity {} to cart {}", in.itemId, in.cartId)
    val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, in.cartId)
    val reply = entityRef.askWithStatus(ShoppingCart.AdjustItemQuantity(in.itemId, in.quantity, _))
    val response = reply.map(toProtoCart)(system.executionContext)
    convertError(response)
  }

  override def checkout(in: CheckoutRequest): Future[Cart] = {
    logger.info("checkout cart {}", in.cartId)
    val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, in.cartId)
    val reply = entityRef.askWithStatus(ShoppingCart.Checkout)
    val response = reply.map(toProtoCart)(system.executionContext)
    convertError(response)
  }

  override def getCart(in: GetCartRequest): Future[Cart] = {
    logger.info("getCart {}", in.cartId)
    val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, in.cartId)
    val response = entityRef.ask(ShoppingCart.Get).map { cart =>
      if (cart.items.isEmpty) {
        throw new GrpcServiceException(
          Status.NOT_FOUND.withDescription(s"Cart ${in.cartId} not found"))
      } else {
        toProtoCart(cart)
      }
    }(system.executionContext)
    convertError(response)
  }

  override def getItemPopularity(
    in: GetItemPopularityRequest
  ): Future[GetItemPopularityResponse] = {
    Future {
      ScalikeJdbcSession.withSession { session =>
        itemPopularityRepository.getItem(session, in.itemId)
      }
    }(blockingJdbcExecutor).map {
      case Some(count) =>
        GetItemPopularityResponse(in.itemId, count)
      case None =>
        GetItemPopularityResponse(in.itemId)
    }(system.executionContext)
  }

  private def toProtoCart(cart: ShoppingCart.Summary): Cart =
    Cart(items = cart.items.map { case (itemId, quantity) =>
      Item(itemId = itemId, quantity = quantity)
      }.toSeq,
      cart.isCheckedOut)

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
