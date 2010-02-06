/* NSC -- new Scala compiler
 * Copyright 2006-2010 LAMP/EPFL
 * @author  Martin Odersky
 */

// $Id$

package scala.tools.nsc
package util

import java.io.File
import java.net.URL
import java.util.StringTokenizer
import scala.util.Sorting

import scala.collection.mutable.{ListBuffer, ArrayBuffer, HashSet => MutHashSet}
import scala.tools.nsc.io.AbstractFile

/** <p>
 *    This module provides star expansion of '-classpath' option arguments, behaves the same as
 *    java, see [http://java.sun.com/javase/6/docs/technotes/tools/windows/classpath.html]
 *  </p>
 *
 *  @author Stepan Koltsov
 */
object ClassPath {
  /** Expand single path entry */
  private def expandS(pattern: String): List[String] = {
    def isJar(name: String) = name.toLowerCase endsWith ".jar"

    /** Get all jars in directory */
    def lsJars(f: File, filt: String => Boolean = _ => true) = {
      val list = f.listFiles()
      if (list eq null) Nil
      else list.filter(f => f.isFile() && filt(f.getName) && isJar(f.getName())).map(_.getPath()).toList
    }

    val suffix = File.separator + "*"

    def basedir(s: String) =
      if (s contains File.separator) s.substring(0, s.lastIndexOf(File.separator))
      else "."

    if (pattern == "*") lsJars(new File("."))
    else if (pattern endsWith suffix) lsJars(new File(pattern dropRight 2))
    else if (pattern contains '*') {
      val regexp = ("^%s$" format pattern.replaceAll("""\*""", """.*""")).r
      lsJars(new File(basedir(pattern)), regexp findFirstIn _ isDefined)
    }
    else List(pattern)
  }

  /** Split path using platform-dependent path separator */
  private def splitPath(path: String): List[String] =
    path split File.pathSeparator toList

  /** Expand path and possibly expanding stars */
  def expandPath(path: String, expandStar: Boolean = true): List[String] =
    if (expandStar) splitPath(path).flatMap(expandS(_))
    else splitPath(path)

  /** A useful name filter. */
  def isTraitImplementation(name: String) = name endsWith "$class.class"

  import java.net.MalformedURLException
  def specToURL(spec: String): Option[URL] =
    try Some(new URL(spec))
    catch { case _: MalformedURLException => None }

  /** A class modeling aspects of a ClassPath which should be
   *  propagated to any classpaths it creates.
   */
  abstract class ClassPathContext[T] {
    /** A filter which can be used to exclude entities from the classpath
     *  based on their name.
     */
    def isValidName(name: String): Boolean = true

    /** From the representation to its identifier.
     */
    def toBinaryName(rep: T): String
  }

  class JavaContext extends ClassPathContext[AbstractFile] {
    def toBinaryName(rep: AbstractFile) = {
      assert(rep.name endsWith ".class", rep.name)
      rep.name dropRight 6
    }
  }

  object DefaultJavaContext extends JavaContext {
    override def isValidName(name: String) = !isTraitImplementation(name)
  }

  /** From the source file to its identifier.
   */
  def toSourceName(f: AbstractFile): String = {
    val nme = f.name
    if (nme.endsWith(".scala"))
      nme dropRight 6
    else if (nme.endsWith(".java"))
      nme dropRight 5
    else
      throw new FatalError("Unexpected source file ending: " + nme)
  }
}
import ClassPath._

/**
 * Represents a package which contains classes and other packages
 */
abstract class ClassPath[T] {
  type AnyClassRep = ClassPath[T]#ClassRep

  /**
   * The short name of the package (without prefix)
   */
  def name: String

  /** Info which should be propagated to any sub-classpaths.
   */
  def context: ClassPathContext[T]

  /** Lists of entities.
   */
  val classes: List[AnyClassRep]
  val packages: List[ClassPath[T]]
  val sourcepaths: List[AbstractFile]

  /** Creation controlled so context is retained.
   */
  def createSourcePath(dir: AbstractFile) = new SourcePath[T](dir, context)

  /**
   * Represents classes which can be loaded with a ClassfileLoader/MSILTypeLoader
   * and / or a SourcefileLoader.
   */
  case class ClassRep(binary: Option[T], source: Option[AbstractFile]) {
    def name: String = binary match {
      case Some(x)  => context.toBinaryName(x)
      case _        =>
        assert(source.isDefined)
        toSourceName(source.get)
    }
  }

  /** Filters for assessing validity of various entities.
   */
  def validClassFile(name: String)  = (name endsWith ".class") && context.isValidName(name)
  def validPackage(name: String)    = (name != "META-INF") && (name != "") && (name.head != '.')
  def validSourceFile(name: String) = validSourceExtensions exists (name endsWith _)
  def validSourceExtensions         = List(".scala", ".java")

  /** Utility */
  protected final def dropExtension(name: String) = name take (name.lastIndexOf('.') - 1)

  /**
   * Find a ClassRep given a class name of the form "package.subpackage.ClassName".
   * Does not support nested classes on .NET
   */
  def findClass(name: String): Option[AnyClassRep] = {
    val i = name.indexOf('.')
    if (i < 0) {
      classes.find(_.name == name)
    } else {
      val pkg = name take i
      val rest = name drop (i + 1)
      (packages find (_.name == pkg) flatMap (_ findClass rest)) map {
        case x: ClassRep  => x
        case x            => throw new FatalError("Unexpected ClassRep '%s' found searching for name '%s'".format(x, name))
      }
    }
  }
  def findSourceFile(name: String): Option[AbstractFile] = {
    findClass(name) match {
      case Some(ClassRep(Some(x: AbstractFile), _)) => Some(x)
      case _                                        => None
    }
  }
}

trait AbstractFileClassPath extends ClassPath[AbstractFile] {
  def createDirectoryPath(dir: AbstractFile): DirectoryClassPath = new DirectoryClassPath(dir, context)
}

/**
 * A Classpath containing source files
 */
class SourcePath[T](dir: AbstractFile, val context: ClassPathContext[T]) extends ClassPath[T] {
  def name = dir.name

  lazy val classes: List[ClassRep] = dir partialMap {
    case f if !f.isDirectory && validSourceFile(f.name) => ClassRep(None, Some(f))
  } toList

  lazy val packages: List[SourcePath[T]] = dir partialMap {
    case f if f.isDirectory && validPackage(f.name) => createSourcePath(f)
  } toList

  val sourcepaths: List[AbstractFile] = List(dir)

  override def toString() = "sourcepath: "+ dir.toString()
}

/**
 * A directory (or a .jar file) containing classfiles and packages
 */
class DirectoryClassPath(dir: AbstractFile, val context: ClassPathContext[AbstractFile]) extends AbstractFileClassPath {
  def name = dir.name

  lazy val classes: List[ClassRep] = dir partialMap {
    case f if !f.isDirectory && validClassFile(f.name) => ClassRep(Some(f), None)
  } toList

  lazy val packages: List[DirectoryClassPath] = dir partialMap {
    case f if f.isDirectory && validPackage(f.name) => createDirectoryPath(f)
  } toList

  val sourcepaths: List[AbstractFile] = Nil

  override def toString() = "directory classpath: "+ dir.toString()
}

/**
 * A classpath unifying multiple class- and sourcepath entries.
 */
abstract class MergedClassPath[T] extends ClassPath[T] {
  outer =>

  protected val entries: List[ClassPath[T]]

  def name = entries.head.name

  lazy val classes: List[AnyClassRep] = {
    val cls = new ListBuffer[AnyClassRep]
    for (e <- entries; c <- e.classes) {
      val name = c.name
      val idx = cls.indexWhere(cl => cl.name == name)
      if (idx >= 0) {
        val existing = cls(idx)
        if (existing.binary.isEmpty && c.binary.isDefined)
          cls(idx) = existing.copy(binary = c.binary)
        if (existing.source.isEmpty && c.source.isDefined)
          cls(idx) = existing.copy(source = c.source)
      } else {
        cls += c
      }
    }
    cls.toList
  }

  lazy val packages: List[ClassPath[T]] = {
    val pkg = new ListBuffer[ClassPath[T]]
    for (e <- entries; p <- e.packages) {
      val name = p.name
      val idx = pkg.indexWhere(pk => pk.name == name)
      if (idx >= 0) {
        pkg(idx) = addPackage(pkg(idx), p)
      } else {
        pkg += p
      }
    }
    pkg.toList
  }

  lazy val sourcepaths: List[AbstractFile] = entries.flatMap(_.sourcepaths)

  private def addPackage(to: ClassPath[T], pkg: ClassPath[T]) = {
    def expand = to match {
      case cp: MergedClassPath[_] => cp.entries
      case _                      => List(to)
    }
    newMergedClassPath(expand :+ pkg)
  }

  private def newMergedClassPath(entrs: List[ClassPath[T]]) =
    new MergedClassPath[T] {
      protected val entries = entrs
      val context = outer.context
    }

  override def toString() = "merged classpath "+ entries.mkString("(", "\n", ")")
}

/**
 * The classpath when compiling with target:jvm. Binary files (classfiles) are represented
 * as AbstractFile. nsc.io.ZipArchive is used to view zip/jar archives as directories.
 */
class JavaClassPath(
  boot: String,
  ext: String,
  user: String,
  source: String,
  Xcodebase: String,
  val context: JavaContext
) extends MergedClassPath[AbstractFile] with AbstractFileClassPath {

  protected val entries: List[ClassPath[AbstractFile]] = assembleEntries()

  private def assembleEntries(): List[ClassPath[AbstractFile]] = {
    import ClassPath._
    val etr = new ListBuffer[ClassPath[AbstractFile]]

    def addFilesInPath(path: String, expand: Boolean, ctr: AbstractFile => ClassPath[AbstractFile] = x => createDirectoryPath(x)) {
      for (fileName <- expandPath(path, expandStar = expand)) {
        val file = AbstractFile.getDirectory(fileName)
        if (file ne null) etr += ctr(file)
      }
    }

    // 1. Boot classpath
    addFilesInPath(boot, false)

    // 2. Ext classpath
    for (fileName <- expandPath(ext, expandStar = false)) {
      val dir = AbstractFile.getDirectory(fileName)
      if (dir ne null) {
        for (file <- dir) {
          val name = file.name.toLowerCase
          if (name.endsWith(".jar") || name.endsWith(".zip") || file.isDirectory) {
            val archive = AbstractFile.getDirectory(new File(dir.file, name))
            if (archive ne null) etr += createDirectoryPath(archive)
          }
        }
      }
    }

    // 3. User classpath
    addFilesInPath(user, true)

    // 4. Codebase entries (URLs)
    {
      for {
        spec <- Xcodebase.trim split " "
        url <- specToURL(spec)
        archive <- Option(AbstractFile getURL url)
      } {
        etr += createDirectoryPath(archive)
      }
    }

    // 5. Source path
    if (source != "")
      addFilesInPath(source, false, x => createSourcePath(x))

    etr.toList
  }
}
