package ammonite.repl.interp

import java.lang.reflect.InvocationTargetException

import acyclic.file
import ammonite.repl.frontend.{ReplExit, ReplAPI}
import ammonite.repl.{ImportData, Evaluated, Result, Catching}
import scala.reflect.runtime.universe._
import scala.collection.mutable
import scala.util.Try

/**
 * Takes source code and, with the help of a compiler and preprocessor,
 * evaluates it and returns a `Result[(output: String, imports: String)]`
 * where `output` is what gets printed and `imports` are any imports that
 * need to get prepended to subsequent commands.
 */
trait Evaluator{
  def evalClass(code: String, wrapperName: String): Result[(Class[_], Seq[ImportData])]
  def update(newImports: Seq[ImportData]): Unit
  def processLine(line: String): Result[Evaluated]
  def previousImportBlock: String
}

object Evaluator{
  def apply(currentClassloader: ClassLoader,
            extraClassLoaders: => Seq[ClassLoader],
            preprocess: (String, Int) => Result[Preprocessor.Output],
            compile: => (Array[Byte], String => Unit) => Compiler.Output,
            stdout: String => Unit): Evaluator = new Evaluator{

    def namesFor(t: scala.reflect.runtime.universe.Type): Set[String] = {
      val yours = t.members.map(_.name.toString).toSet
      val default = typeOf[Object].members.map(_.name.toString)
      yours -- default
    }

    /**
     * Files which have been compiled, stored so that our special
     * classloader can get at them.
     */
    val newFileDict = mutable.Map.empty[String, Array[Byte]]
    /**
     * Imports which are required by earlier commands to the REPL. Imports
     * have a specified key, so that later imports of the same name (e.g.
     * defining a variable twice) can kick the earlier import out of the
     * map. Otherwise if you import the same name twice you get compile
     * errors instead of the desired shadowing.
     */
    val previousImports = mutable.Map(
      namesFor(typeOf[ReplAPI]).map(n => n -> ImportData(n, "", "ReplBridge.shell")).toSeq:_*
    )

    /**
     * The current line number of the REPL, used to make sure every snippet
     * evaluated can have a distinct name that doesn't collide.
     */
    var currentLine = 0
    /**
     * Performs the conversion of our pre-compiled `Array[Byte]`s into
     * actual classes with methods we can execute.
     */
    val evalClassloader = new ClassLoader(currentClassloader) {
      override def loadClass(name: String): Class[_] = {
        if (newFileDict.contains(name)) {
          defineClass(name, newFileDict(name), 0, newFileDict(name).length)
        }else{
          try{
            super.loadClass(name)
          }catch{ case e: ClassNotFoundException =>
            val classes = for(cl <- extraClassLoaders.iterator) yield {
              try Some(cl.loadClass(name))
              catch{ case e: ClassNotFoundException => None}
            }
            classes.collectFirst{ case Some(cls) => cls}
              .getOrElse{ throw new ClassNotFoundException(name) }
          }
        }
      }
    }

    def evalClass(code: String, wrapperName: String) = for{
      (output, compiled) <- Result(
        Try{
          val output = mutable.Buffer.empty[String]
          val c = compile(code.getBytes, output.append(_))
          (output, c)
        },
        e => {stdout("!!!! " + e.printStackTrace()); e.toString}
      )

      (classFiles, importData) <- Result[(Traversable[(String, Array[Byte])], Seq[ImportData])](
        compiled, "Compilation Failed\n" + output.mkString("\n")
      )

      cls <- Result[Class[_]](Try {
        for ((name, bytes) <- classFiles) {
          newFileDict(name) = bytes
        }

        Class.forName(wrapperName , true, evalClassloader)
      }, e => "Failed to load compiled class " + e)
    } yield (cls, importData)

    def evalMain(cls: Class[_]) = for{
      _ <- Result.Success(())
      method = cls.getDeclaredMethod("$main")

    } yield {
      val res = method.invoke(null)
      res
    }

    var evalId = 0

    def evalExpr(code: String) = {
      val wrapperId = "$eval" + evalId
      evalId += 1
      for{
        (cls, imports) <- evalClass(s"""
      object $wrapperId{
        def $$main() = {
          $code
        }
      }""",
          wrapperId
        )
        evaled <- evalMain(cls)
      } yield evaled

    }

    def previousImportBlock = {
      previousImports
        .values
        .groupBy(_.prefix)
        .map{case (prefix, imports) =>
        s"import $prefix.{${imports.map("`"+_.imported+"`").mkString(",")}}"
      }
        .mkString("\n")
    }
    def processLine(line: String) = for {
      Preprocessor.Output(code, printer) <- preprocess(line, currentLine)

      wrapperName = "cmd" + currentLine

      wrapped = s"""
      object $wrapperName{
        $code
        def $$main() = {$printer}
      }
    """
      //    _ = println(wrapped)
      wrappedWithImports = previousImportBlock + "\n\n" + wrapped
      (cls, newImports) <- evalClass(wrappedWithImports, wrapperName)
      _ = currentLine += 1
      evaled <- evalMain(cls)
    } yield Evaluated(
        evaled.asInstanceOf[Iterator[String]], wrapperName ,
        newImports.map(id => id.copy(
          wrapperName = wrapperName,
          prefix = if (id.prefix == "") wrapperName else id.prefix)
        )
      )

    def update(newImports: Seq[ImportData]) = {
      for(i <- newImports) previousImports(i.imported) = i
    }
  }

}