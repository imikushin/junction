package org.killingbilling.junction

import java.io.ByteArrayOutputStream
import java.lang.{Double => JDouble}
import java.nio.file.Paths
import java.util.{List => JList, Map => JMap}
import javax.script.{ScriptContext, Invocable}
import org.killingbilling.junction.utils._
import org.scalatest.{Matchers, FreeSpec}
import scala.collection.JavaConversions._
import scala.compat.Platform

object ModuleSpec {

  val workDir = Paths.get(".").toAbsolutePath.normalize()
  def resolvedPath(s: String) = workDir.resolve(s).normalize().toString

  def jsObj(o: Map[String, AnyRef]): JMap[String, AnyRef] = o
  def jsArr(o: List[AnyRef]): JList[AnyRef] = o

  val out = new ByteArrayOutputStream()
  val err = new ByteArrayOutputStream()
  Console.setOut(out)
  Console.setErr(err)

  trait Aggregate {
    def aggr(a: Double, b: Double): Double
    def init(v: Double): Double
  }
  trait Account {
    def aggregates: JMap[String, Aggregate]
  }
  trait ServiceAccount extends Account
  trait ServiceAccountFactory {
    def instance(obj: AnyRef): ServiceAccount
  }
  trait WrapO {
    def map[K,V](o: AnyRef): JMap[K,V]
    def list[T](o: AnyRef): JList[T]
  }

}

class ModuleSpec extends FreeSpec with Matchers {

  import ModuleSpec._

  "require.resolve()" in {
    val require = Require()
    require.resolve("lib/dummy") shouldBe resolvedPath("./node_modules/lib/dummy.js")
    require.resolve("./src/test/js/dumb.js") shouldBe resolvedPath("./src/test/js/dumb.js.js")
    require.resolve("./src/test/js/d") shouldBe resolvedPath("./src/test/js/d/lib/main.js")
    require.resolve("./src/test/js/d.js") shouldBe resolvedPath("./src/test/js/d.js/index.js")
  }

  "require()" in {
    val require = Require()
    val wrapo = require.impl("wrap-o", classOf[WrapO])
    val wm = wrapo.map[String, AnyRef] _
    val wl = wrapo.list[AnyRef] _
    wm(require("./src/test/js/dummy.txt")) shouldBe jsObj(Map("dummyID" -> "dummy"))
    wm(require("./src/test/js/someObj.json")) shouldBe jsObj(Map("qq" -> "QQ", "n" -> (2.0: JDouble)))
    wl(require("./src/test/js/someArr.json")) shouldBe jsArr(List(4.0: JDouble, "abra", "cada", 2.0: JDouble, "bra"))
    require("./src/test/js/d") shouldBe "(arg: QQ)"
    require("./src/test/js/d.js") shouldBe "(arg: QQ.js)"
  }

  "require() cycle" in {
    val require = Require()
    val expectedOutput = """
                           |main starting
                           |a starting
                           |b starting
                           |in b, a.done = false
                           |b done
                           |in a, b.done = true
                           |a done
                           |in main, a.done=true, b.done=true
                         """.stripMargin
    out.reset()
    require("./src/test/js/main")
    out.toString(Platform.defaultCharsetName).trim shouldBe expectedOutput.trim
  }

  "process" in {
    val require = Require()
    val wrapo = require.impl("wrap-o", classOf[WrapO])
    val p = wrapo.map[String, AnyRef](require("./src/test/js/process.js")).toMap
    p("noDeprecation") shouldBe false
    p("throwDeprecation") shouldBe true
    p("traceDeprecation") shouldBe true
  }

  "process.stdout" in {
    val require = Require()
    out.reset()
    require("./src/test/js/writeHello.js")
    out.toString(Platform.defaultCharsetName) shouldBe "HELLO!"
  }

  "load lib/console.js" in {
    getClass.getClassLoader.getResource("lib/console.js") shouldNot be(null)
  }

  "console.log" in {
    val require = Require()
    out.reset()
    require("./src/test/js/logHello.js")
    out.toString(Platform.defaultCharsetName) shouldBe "LOGGING HELLO!\n"
  }

  "Buffer" in {
    val require = Require()
    val wrapo = require.impl("wrap-o", classOf[WrapO])
    val a = wrapo.map[String, AnyRef](require("./src/test/js/ass.js")).toMap
    a("isBuffer") shouldBe false
  }

  "require.impl()" in {
    val require = Require()
    val agg: Aggregate = require.impl("./src/test/js/agg.js", classOf[Aggregate])
    val agg2: Aggregate = require.impl("./src/test/js/agg2.js", classOf[Aggregate])

    agg.aggr(1, 2) shouldBe 3
    agg.init(4) shouldBe 0

    agg2.aggr(1, 2) shouldBe 2
    agg2.init(4) shouldBe 1
  }

  "impl non-trivial types" in {
    val require = Require()
    val accFactory = require("lib/ServiceAccountFactory.js").asInstanceOf[ServiceAccountFactory]
    val acc = accFactory.instance(require("./src/test/js/acc.js"))

    val agg = acc.aggregates.get("prod")
    agg.aggr(3, 2) shouldBe 6
    agg.init(0) shouldBe 1
  }

  "require.impl() cache" in {
    val require = Require()
    val agg: Aggregate = require.impl("./src/test/js/agg.js", classOf[Aggregate])
    val agg2: Aggregate = require.impl("./src/test/js/../js/agg.js", classOf[Aggregate])

    agg2 shouldBe theSameInstanceAs(agg)
  }

  "module.exports - impl interface" in {
    val js = newEngine()
    js.getContext.getBindings(ScriptContext.GLOBAL_SCOPE).put("module", new Module())

    js.eval( """
               | 'use strict';
               | module.exports = {
               |   aggr: function(a, b) {return a + b;},
               |   init: function(v) {return (v == null) ? 0 : v;}
               | };
               | """.stripMargin)

    val locals = js.createBindings()
    val obj = js.eval("'use strict'; module.exports", locals)
    val agg = js.asInstanceOf[Invocable].getInterface(obj, classOf[Aggregate])

    agg.aggr(1, 2) shouldBe 3
    agg.init(1) shouldBe 1
  }

}
