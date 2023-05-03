package shopping.cart

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.pattern.StatusReply
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike
import shopping.cart.ShoppingCart.{Command, Event, State}

object ShoppingCartSpec {
  val config: Config = ConfigFactory.parseString(
    """
    akka.actor.serialization-bindings {
      "shopping.cart.CborSerializable" = jackson-cbor
    }
    """)
    .withFallback(EventSourcedBehaviorTestKit.config)
}

class ShoppingCartSpec
  extends ScalaTestWithActorTestKit(ShoppingCartSpec.config)
    with AnyWordSpecLike
    with BeforeAndAfterEach {

  private val cartId = "testCart"
  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[Command, Event, State](
    system,
    ShoppingCart(cartId)
  )

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "The Shopping Cart" must {
    "be able to add an item" in {
      val result = eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      result.reply shouldBe StatusReply.Success(ShoppingCart.Summary(Map("foo" -> 42), isCheckedOut = false))
      result.event shouldBe ShoppingCart.ItemAdded("foo", cartId, 42)
      result.state shouldBe ShoppingCart.State(Map("foo" -> 42), None)
    }

    "be able to add multiple items" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      val result = eventSourcedTestKit.runCommand(ShoppingCart.AddItem("bar", 17, _))

      result.reply shouldBe StatusReply.Success(ShoppingCart.Summary(Map("foo" -> 42, "bar" -> 17), isCheckedOut = false))
      result.event shouldBe ShoppingCart.ItemAdded("bar", cartId, 17)
      result.state shouldBe ShoppingCart.State(Map("foo" -> 42, "bar" -> 17), None)
    }

    "reject adding an existing item" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      val result = eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 1, _))

      result.reply shouldBe StatusReply.Error("Item was already added to this shopping cart")
      result.hasNoEvents shouldBe true
      result.state shouldBe ShoppingCart.State(Map("foo" -> 42), None)
    }

    "reject an item with negative quantity" in {
      val result = eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", -42, _))
      result.reply shouldBe StatusReply.Error("Quantity must be greater than zero")
      result.hasNoEvents shouldBe true
      result.state shouldBe ShoppingCart.State.empty
    }

    "be able to remove an item" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      val result = eventSourcedTestKit.runCommand(ShoppingCart.RemoveItem("foo", _))

      result.reply shouldBe StatusReply.Success(ShoppingCart.Summary(Map.empty, isCheckedOut = false))
      result.event shouldBe ShoppingCart.ItemRemoved("foo", cartId)
      result.state shouldBe ShoppingCart.State.empty
    }

    "reject removing an item it doesn't contain" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      val result = eventSourcedTestKit.runCommand(ShoppingCart.RemoveItem("bar", _))

      result.reply shouldBe StatusReply.Error("Item not found")
      result.hasNoEvents shouldBe true
      result.state shouldBe ShoppingCart.State(Map("foo" -> 42), None)
    }

    "be able to adjust an item's quantity" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      val result = eventSourcedTestKit.runCommand(ShoppingCart.AdjustItemQuantity("foo", 17, _))

      result.reply shouldBe StatusReply.Success(ShoppingCart.Summary(Map("foo" -> 17), isCheckedOut = false))
      result.event shouldBe ShoppingCart.ItemQuantityAdjusted("foo", cartId, 17)
      result.state shouldBe ShoppingCart.State(Map("foo" -> 17), None)
    }

    "reject adjusting an item's quantity if it's negative" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      val result = eventSourcedTestKit.runCommand(ShoppingCart.AdjustItemQuantity("foo", -17, _))

      result.reply shouldBe StatusReply.Error("Quantity must be greater than zero")
      result.hasNoEvents shouldBe true
      result.state shouldBe ShoppingCart.State(Map("foo" -> 42), None)
    }

    "reject adjusting a nonexistent item's quantity" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      val result = eventSourcedTestKit.runCommand(ShoppingCart.AdjustItemQuantity("bar", 17, _))

      result.reply shouldBe StatusReply.Error("Item not found")
      result.hasNoEvents shouldBe true
      result.state shouldBe ShoppingCart.State(Map("foo" -> 42), None)
    }

    "be able to checkout" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      val result = eventSourcedTestKit.runCommand(ShoppingCart.Checkout)

      result.reply shouldBe StatusReply.Success(ShoppingCart.Summary(Map("foo" -> 42), isCheckedOut = true))
      result.event shouldBe a[ShoppingCart.CheckedOut]
      result.state.isCheckedOut shouldBe true
      result.state.items shouldBe Map("foo" -> 42)
    }

    "not be able to checkout an empty cart" in {
      val result = eventSourcedTestKit.runCommand(ShoppingCart.Checkout)
      result.reply shouldBe StatusReply.Error("Can't checkout an empty shopping cart")
      result.hasNoEvents shouldBe true
      result.state shouldBe ShoppingCart.State.empty
    }

    "reject adding an item after checkout" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      eventSourcedTestKit.runCommand(ShoppingCart.Checkout)
      val result = eventSourcedTestKit.runCommand(ShoppingCart.AddItem("bar", 17, _))
      result.reply shouldBe StatusReply.Error("Can't add an item to a checked out shopping cart")
      result.hasNoEvents shouldBe true
      result.state.isCheckedOut shouldBe true
      result.state.items shouldBe Map("foo" -> 42)
    }

    "reject removing an item after checkout" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      eventSourcedTestKit.runCommand(ShoppingCart.Checkout)
      val result = eventSourcedTestKit.runCommand(ShoppingCart.RemoveItem("foo", _))
      result.reply shouldBe StatusReply.Error("Can't remove an item from a checked out shopping cart")
      result.hasNoEvents shouldBe true
      result.state.isCheckedOut shouldBe true
      result.state.items shouldBe Map("foo" -> 42)
    }

    "reject adjusting an item's quantity after checkout" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      eventSourcedTestKit.runCommand(ShoppingCart.Checkout)
      val result = eventSourcedTestKit.runCommand(ShoppingCart.AdjustItemQuantity("foo", 17, _))
      result.reply shouldBe StatusReply.Error("Can't adjust item quantity in a checked out shopping cart")
      result.hasNoEvents shouldBe true
      result.state.isCheckedOut shouldBe true
      result.state.items shouldBe Map("foo" -> 42)
    }

    "not be able to checkout an already checked out cart" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      eventSourcedTestKit.runCommand(ShoppingCart.Checkout)
      val result = eventSourcedTestKit.runCommand(ShoppingCart.Checkout)
      result.reply shouldBe StatusReply.Error("Can't checkout an already checked out shopping cart")
      result.hasNoEvents shouldBe true
      result.state.isCheckedOut shouldBe true
      result.state.items shouldBe Map("foo" -> 42)
    }

    "get the current state" in {
      eventSourcedTestKit.runCommand(ShoppingCart.AddItem("foo", 42, _))
      eventSourcedTestKit.runCommand(ShoppingCart.Checkout)
      val result = eventSourcedTestKit.runCommand(ShoppingCart.Get)
      result.reply shouldBe ShoppingCart.Summary(Map("foo" -> 42), isCheckedOut = true)
    }


  }

}
