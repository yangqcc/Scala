/* NSC -- new Scala compiler
 * Copyright 2005-2010 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$

package scala.tools.nsc
package symtab
package classfile

import java.io.IOException
import java.lang.Integer.toHexString

import scala.collection.immutable.{Map, ListMap}
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.util.{Position, NoPosition, ClassRep}
import scala.annotation.switch

/** This abstract class implements a class file parser.
 *
 *  @author Martin Odersky
 *  @version 1.0
 */
abstract class ClassfileParser {
  val global: Global
  import global._

  import ClassfileConstants._
  import Flags._

  protected var in: AbstractFileReader = _  // the class file reader
  protected var clazz: Symbol = _           // the class symbol containing dynamic members
  protected var staticModule: Symbol = _    // the module symbol containing static members
  protected var instanceDefs: Scope = _     // the scope of all instance definitions
  protected var staticDefs: Scope = _       // the scope of all static definitions
  protected var pool: ConstantPool = _      // the classfile's constant pool
  protected var isScala: Boolean = _        // does class file describe a scala class?
  protected var isScalaRaw: Boolean = _     // this class file is a scala class with no pickled info
  protected var hasMeta: Boolean = _        // does class file contain jaco meta attribute?s
  protected var busy: Option[Symbol] = None // lock to detect recursive reads
  private var   externalName: Name = _      // JVM name of the current class
  protected var classTParams = Map[Name,Symbol]()

  private object metaParser extends MetaParser {
    val global: ClassfileParser.this.global.type = ClassfileParser.this.global
  }

  private object unpickler extends UnPickler {
    val global: ClassfileParser.this.global.type = ClassfileParser.this.global
  }

  def parse(file: AbstractFile, root: Symbol) = try {
    def handleMissing(e: MissingRequirementError) = {
      if (settings.debug.value) e.printStackTrace
      throw new IOException("Missing dependency '" + e.req + "', required by " + in.file)
    }

    def handleError(e: Exception) = {
      if (settings.debug.value) e.printStackTrace()
      throw new IOException("class file '" + in.file + "' is broken\n(" + {
        if (e.getMessage() != null) e.getMessage()
        else e.getClass.toString
      } + ")")
    }
    assert(!busy.isDefined, {
      val (s1, s2) = (busy.get, root)
      if (s1 eq s2) "unsatisfiable cyclic dependency in '%s'".format(s1)
      else "illegal class file dependency between '%s' and '%s'".format(s1, s2)
    })

    busy = Some(root)
    /*root match {
      case cs: ClassSymbol =>
        cs.classFile = file
      case ms: ModuleSymbol =>
        ms.moduleClass.asInstanceOf[ClassSymbol].classFile = file
      case _ =>
        println("Skipping class: " + root + ": " + root.getClass)
    }
*/
    this.in = new AbstractFileReader(file)
    if (root.isModule) {
      this.clazz = root.linkedClassOfModule
      this.staticModule = root
    } else {
      this.clazz = root
      this.staticModule = root.linkedModuleOfClass
    }
    this.isScala = false
    this.hasMeta = false
    try {
      parseHeader
      this.pool = new ConstantPool
      parseClass()
    } catch {
      case e: MissingRequirementError => handleMissing(e)
      case e: RuntimeException        => handleError(e)
    }
  } finally {
    busy = None
  }

  protected def statics: Symbol = staticModule.moduleClass

  private def parseHeader {
    val magic = in.nextInt
    if (magic != JAVA_MAGIC)
      throw new IOException("class file '" + in.file + "' "
                            + "has wrong magic number 0x" + toHexString(magic)
                            + ", should be 0x" + toHexString(JAVA_MAGIC))
    val minorVersion = in.nextChar.toInt
    val majorVersion = in.nextChar.toInt
    if ((majorVersion < JAVA_MAJOR_VERSION) ||
        ((majorVersion == JAVA_MAJOR_VERSION) &&
         (minorVersion < JAVA_MINOR_VERSION)))
      throw new IOException("class file '" + in.file + "' "
                            + "has unknown version "
                            + majorVersion + "." + minorVersion
                            + ", should be at least "
                            + JAVA_MAJOR_VERSION + "." + JAVA_MINOR_VERSION)
  }

  class ConstantPool {
    private val len = in.nextChar
    private val starts = new Array[Int](len)
    private val values = new Array[AnyRef](len)
    private val internalized = new Array[Name](len)

    { var i = 1
      while (i < starts.length) {
        starts(i) = in.bp
        i += 1
        (in.nextByte.toInt: @switch) match {
          case CONSTANT_UTF8 | CONSTANT_UNICODE =>
            in.skip(in.nextChar)
          case CONSTANT_CLASS | CONSTANT_STRING =>
            in.skip(2)
          case CONSTANT_FIELDREF | CONSTANT_METHODREF | CONSTANT_INTFMETHODREF
             | CONSTANT_NAMEANDTYPE | CONSTANT_INTEGER | CONSTANT_FLOAT =>
            in.skip(4)
          case CONSTANT_LONG | CONSTANT_DOUBLE =>
            in.skip(8)
            i += 1
          case _ =>
            errorBadTag(in.bp - 1)
        }
      }
    }

    /** Return the name found at given index. The returned name is a term name. */
    def getName(index: Int): Name = {
      if (index <= 0 || len <= index) errorBadIndex(index)
      var name = values(index).asInstanceOf[Name]
      if (name eq null) {
        val start = starts(index)
        if (in.buf(start).toInt != CONSTANT_UTF8) errorBadTag(start)
        name = newTermName(in.buf, start + 3, in.getChar(start + 1))
        values(index) = name
      }
      name
    }

    /** Return the name found at given index in the constant pool, with '/' replaced by '.'. */
    def getExternalName(index: Int): Name = {
      if (index <= 0 || len <= index) errorBadIndex(index)
      if (internalized(index) eq null) {
        internalized(index) = getName(index).replace('/', '.')
      }
      internalized(index)
    }

    def getClassSymbol(index: Int): Symbol = {
      if (index <= 0 || len <= index) errorBadIndex(index)
      var c = values(index).asInstanceOf[Symbol]
      if (c eq null) {
        val start = starts(index)
        if (in.buf(start).toInt != CONSTANT_CLASS) errorBadTag(start)
        val name = getExternalName(in.getChar(start + 1))
        if (name.endsWith("$"))
          c = definitions.getModule(name.subName(0, name.length - 1))
        else
          c = classNameToSymbol(name)
        values(index) = c
      }
      c
    }

    /** Return the external name of the class info structure found at 'index'.
     *  Use 'getClassSymbol' if the class is sure to be a top-level class.
     */
    def getClassName(index: Int): Name = {
      val start = starts(index)
      if (in.buf(start).toInt != CONSTANT_CLASS) errorBadTag(start)
      getExternalName(in.getChar(start + 1))
    }

    /** Return the symbol of the class member at <code>index</code>.
     *  The following special cases exist:
     *   - If the member refers to special MODULE$ static field, return
     *  the symbol of the corresponding module.
     *   - If the member is a field, and is not found with the given name,
     *     another try is made by appending nme.LOCAL_SUFFIX
     *   - If no symbol is found in the right tpe, a new try is made in the
     *     companion class, in case the owner is an implementation class.
     */
    def getMemberSymbol(index: Int, static: Boolean): Symbol = {
      if (index <= 0 || len <= index) errorBadIndex(index)
      var f = values(index).asInstanceOf[Symbol]
      if (f eq null) {
        val start = starts(index)
        val first = in.buf(start).toInt
        if (first != CONSTANT_FIELDREF &&
            first != CONSTANT_METHODREF &&
            first != CONSTANT_INTFMETHODREF) errorBadTag(start)
        val ownerTpe = getClassOrArrayType(in.getChar(start + 1))
        if (settings.debug.value)
          log("getMemberSymbol(static: " + static + "): owner type: " + ownerTpe + " " + ownerTpe.typeSymbol.originalName)
        val (name, tpe) = getNameAndType(in.getChar(start + 3), ownerTpe)
        if (settings.debug.value)
          log("getMemberSymbol: name and tpe: " + name + ": " + tpe)
        if (name == nme.MODULE_INSTANCE_FIELD) {
          val index = in.getChar(start + 1)
          val name = getExternalName(in.getChar(starts(index) + 1))
          //assert(name.endsWith("$"), "Not a module class: " + name)
          f = forceMangledName(name.subName(0, name.length - 1), true)
          if (f == NoSymbol)
            f = definitions.getModule(name.subName(0, name.length - 1))
        } else {
          val origName = nme.originalName(name)
          val owner = if (static) ownerTpe.typeSymbol.linkedClassOfClass else ownerTpe.typeSymbol
//          println("\t" + owner.info.member(name).tpe.widen + " =:= " + tpe)
          f = owner.info.member(origName).suchThat(_.tpe.widen =:= tpe)
          if (f == NoSymbol)
            f = owner.info.member(newTermName(origName.toString + nme.LOCAL_SUFFIX)).suchThat(_.tpe =:= tpe)
          if (f == NoSymbol) {
            // if it's an impl class, try to find it's static member inside the class
            if (ownerTpe.typeSymbol.isImplClass)
              f = ownerTpe.member(origName).suchThat(_.tpe =:= tpe)
            else {
              log("Couldn't find " + name + ": " + tpe + " inside: \n" + ownerTpe)
              f = if (tpe.isInstanceOf[MethodType]) owner.newMethod(owner.pos, name).setInfo(tpe)
                  else owner.newValue(owner.pos, name).setInfo(tpe).setFlag(MUTABLE)
              log("created fake member " + f.fullNameString)
            }
//            println("\townerTpe.decls: " + ownerTpe.decls)
//            println("Looking for: " + name + ": " + tpe + " inside: " + ownerTpe.typeSymbol + "\n\tand found: " + ownerTpe.members)
          }
        }
        assert(f != NoSymbol, "could not find " + name + ": " + tpe + "inside: \n" + ownerTpe.members)
        values(index) = f
      }
      f
    }

    /** Return a name and a type at the given index. If the type is a method
     *  type, a dummy symbol is created in 'ownerTpe', which is used as the
     *  owner of its value parameters. This might lead to inconsistencies,
     *  if a symbol of the given name already exists, and has a different
     *  type.
     */
    private def getNameAndType(index: Int, ownerTpe: Type): (Name, Type) = {
      if (index <= 0 || len <= index) errorBadIndex(index)
      var p = values(index).asInstanceOf[(Name, Type)]
      if (p eq null) {
        val start = starts(index)
        if (in.buf(start).toInt != CONSTANT_NAMEANDTYPE) errorBadTag(start)
        val name = getName(in.getChar(start + 1).toInt)
        // create a dummy symbol for method types
        val dummySym = ownerTpe.typeSymbol.newMethod(ownerTpe.typeSymbol.pos, name)
        var tpe  = getType(dummySym, in.getChar(start + 3).toInt)

        // fix the return type, which is blindly set to the class currently parsed
        if (name == nme.CONSTRUCTOR)
          tpe match {
            case MethodType(formals, restpe) =>
              tpe = MethodType(formals, ownerTpe)
          }

        p = (name, tpe)
      }
      p
    }

    /** Return the type of a class constant entry. Since
     *  arrays are considered to be class types, they might
     *  appear as entries in 'newarray' or 'cast' opcodes.
     */
    def getClassOrArrayType(index: Int): Type = {
      if (index <= 0 || len <= index) errorBadIndex(index)
      val value = values(index)
      var c: Type = null
      if (value eq null) {
        val start = starts(index)
        if (in.buf(start).toInt != CONSTANT_CLASS) errorBadTag(start)
        val name = getExternalName(in.getChar(start + 1))
        if (name(0) == ARRAY_TAG) {
          c = sigToType(null, name)
          values(index) = c
        } else {
          val sym = classNameToSymbol(name)
                  /*if (name.endsWith("$")) definitions.getModule(name.subName(0, name.length - 1))
                    else if (name.endsWith("$class")) definitions.getModule(name)
                    else definitions.getClass(name)*/
          values(index) = sym
          c = sym.tpe
        }
      } else c = value match {
          case tp: Type => tp
          case cls: Symbol => cls.tpe
      }
      c
    }

    def getType(index: Int): Type = getType(null, index)

    def getType(sym: Symbol, index: Int): Type =
      sigToType(sym, getExternalName(index))

    def getSuperClass(index: Int): Symbol =
      if (index == 0) definitions.AnyClass else getClassSymbol(index)

    def getConstant(index: Int): Constant = {
      if (index <= 0 || len <= index) errorBadIndex(index)
      var value = values(index)
      if (value eq null) {
        val start = starts(index)
        value = (in.buf(start).toInt: @switch) match {
          case CONSTANT_STRING =>
            Constant(getName(in.getChar(start + 1).toInt).toString())
          case CONSTANT_INTEGER =>
            Constant(in.getInt(start + 1))
          case CONSTANT_FLOAT =>
            Constant(in.getFloat(start + 1))
          case CONSTANT_LONG =>
            Constant(in.getLong(start + 1))
          case CONSTANT_DOUBLE =>
            Constant(in.getDouble(start + 1))
          case CONSTANT_CLASS =>
            getClassOrArrayType(index).typeSymbol
          case _ =>
            errorBadTag(start)
        }
        values(index) = value
      }
      value match {
        case  ct: Constant => ct
        case cls: Symbol   => Constant(cls.tpe)
        case arr: Type     => Constant(arr)
      }
    }

    /** Throws an exception signaling a bad constant index. */
    private def errorBadIndex(index: Int) =
      throw new RuntimeException("bad constant pool index: " + index + " at pos: " + in.bp)

    /** Throws an exception signaling a bad tag at given address. */
    private def errorBadTag(start: Int) =
      throw new RuntimeException("bad constant pool tag " + in.buf(start) + " at byte " + start)
  }

  /** Try to force the chain of enclosing classes for the given name. Otherwise
   *  flatten would not lift classes that were not referenced in the source code.
   */
  def forceMangledName(name: Name, module: Boolean): Symbol = {
    val parts = name.toString.split(Array('.', '$'))
    var sym: Symbol = definitions.RootClass
    atPhase(currentRun.flattenPhase.prev) {
      for (part0 <- parts; val part = newTermName(part0)) {
        val sym1 = sym.info.decl(part.encode)//.suchThat(module == _.isModule)
        if (sym1 == NoSymbol)
          sym = sym.info.decl(part.encode.toTypeName)
        else
          sym = sym1
      }
    }
//    println("found: " + sym)
    sym
  }


  /** Return the class symbol of the given name. */
  def classNameToSymbol(name: Name): Symbol = {
    def lookupClass(name: Name) =
      if (name.pos('.') == name.length)
        definitions.getMember(definitions.EmptyPackageClass, name.toTypeName)
      else
        definitions.getClass(name)

    innerClasses.get(name) match {
      case Some(entry) =>
        //println("found inner class " + name)
        val res = innerClasses.classSymbol(entry.externalName)
        //println("\trouted to: " + res)
        res
      case None =>
        //if (name.toString.contains("$")) println("No inner class: " + name + innerClasses + " while parsing " + in.file.name)
        lookupClass(name)
    }
  }

  var sawPrivateConstructor = false

  def parseClass() {
    val jflags = in.nextChar
    val isAnnotation = hasAnnotation(jflags)
    var sflags = transFlags(jflags, true)
    var nameIdx = in.nextChar
    externalName = pool.getClassName(nameIdx)
    val c = if (externalName.toString.indexOf('$') < 0) pool.getClassSymbol(nameIdx) else clazz

    /** Parse parents for Java classes. For Scala, return AnyRef, since the real type will be unpickled.
     *  Updates the read pointer of 'in'. */
    def parseParents: List[Type] = {
      if (isScala) {
        in.nextChar              // skip superclass
        val ifaces = in.nextChar
        in.bp += ifaces * 2     // .. and iface count interfaces
        List(definitions.AnyRefClass.tpe) // dummy superclass, will be replaced by pickled information
      } else {
        val superType = if (isAnnotation) { in.nextChar; definitions.AnnotationClass.tpe }
                        else pool.getSuperClass(in.nextChar).tpe
        val ifaceCount = in.nextChar
        var ifaces = for (i <- List.range(0, ifaceCount)) yield pool.getSuperClass(in.nextChar).tpe
        if (isAnnotation) ifaces = definitions.ClassfileAnnotationClass.tpe :: ifaces
        superType :: ifaces
      }
    }

    if (c != clazz && externalName.toString.indexOf("$") < 0) {
      if ((clazz eq NoSymbol) && (c ne NoSymbol)) clazz = c
      else throw new IOException("class file '" + in.file + "' contains wrong " + c)
    }

    addEnclosingTParams(clazz)
    parseInnerClasses() // also sets the isScala / isScalaRaw / hasMeta flags, see r15956
    // get the class file parser to reuse scopes.
    instanceDefs = new Scope
    staticDefs = new Scope
    val classInfo = ClassInfoType(parseParents, instanceDefs, clazz)
    val staticInfo = ClassInfoType(List(), staticDefs, statics)

    if (!isScala && !isScalaRaw) {
//      println("Entering inner classes for " + clazz)
      enterOwnInnerClasses
    }
    val curbp = in.bp
    skipMembers() // fields
    skipMembers() // methods
    if (!isScala) {
      clazz.setFlag(sflags)
      setPrivateWithin(clazz, jflags)
      setPrivateWithin(staticModule, jflags)
      if (!hasMeta || isScalaRaw) {
        clazz.setInfo(classInfo)
      }
      statics.setInfo(staticInfo)
      staticModule.setInfo(statics.tpe)
      staticModule.setFlag(JAVA)
      staticModule.moduleClass.setFlag(JAVA)
      // attributes now depend on having infos set already
      parseAttributes(clazz, classInfo)

      in.bp = curbp
      val fieldCount = in.nextChar
      for (i <- 0 until fieldCount) parseField()
      sawPrivateConstructor = false
      val methodCount = in.nextChar
      for (i <- 0 until methodCount) parseMethod()
      if (!sawPrivateConstructor &&
          (instanceDefs.lookup(nme.CONSTRUCTOR) == NoSymbol &&
           (sflags & INTERFACE) == 0L))
        {
          //Console.println("adding constructor to " + clazz);//DEBUG
          instanceDefs.enter(
            clazz.newConstructor(NoPosition)
            .setFlag(clazz.flags & ConstrFlags)
            .setInfo(MethodType(List(), clazz.tpe)))
        }
    } else
      parseAttributes(clazz, classInfo)
  }

  /** Add type parameters of enclosing classes */
  def addEnclosingTParams(clazz: Symbol) {
    var sym = clazz.owner
    while (sym.isClass && !sym.isModuleClass) {
//      println("adding tparams of " + sym)
      for (t <- sym.tpe.typeArgs) {
//        println("\tadding " + (t.typeSymbol.name + "->" + t.typeSymbol))
        classTParams = classTParams + (t.typeSymbol.name -> t.typeSymbol)
      }
      sym = sym.owner
    }
  }

  def parseField() {
    val jflags = in.nextChar
    var sflags = transFlags(jflags, false)
    if ((sflags & FINAL) == 0L) sflags = sflags | MUTABLE
    if ((sflags & PRIVATE) != 0L && !global.settings.XO.value) {
      in.skip(4); skipAttributes()
    } else {
      val name = pool.getName(in.nextChar)
      val info = pool.getType(in.nextChar)
      val sym = getOwner(jflags)
        .newValue(NoPosition, name).setFlag(sflags)
      sym.setInfo(if ((jflags & JAVA_ACC_ENUM) == 0) info else mkConstantType(Constant(sym)))
      setPrivateWithin(sym, jflags)
      parseAttributes(sym, info)
      getScope(jflags).enter(sym)
    }
  }

  def parseMethod() {
    val jflags = in.nextChar.toInt
    var sflags = transFlags(jflags, false)
    if (isPrivate(jflags) && !global.settings.XO.value) {
      val name = pool.getName(in.nextChar)
      if (name == nme.CONSTRUCTOR)
        sawPrivateConstructor = true
      in.skip(2); skipAttributes()
    } else {
      if ((jflags & JAVA_ACC_BRIDGE) != 0)
        sflags |= BRIDGE
      if ((sflags & PRIVATE) != 0L && global.settings.XO.value) {
        in.skip(4); skipAttributes()
      } else {
        val name = pool.getName(in.nextChar)
        val sym = getOwner(jflags).newMethod(NoPosition, name).setFlag(sflags)
        var info = pool.getType(sym, (in.nextChar))
        if (name == nme.CONSTRUCTOR)
          info match {
            case MethodType(params, restpe) =>
              // if this is a non-static inner class, remove the explicit outer parameter
              val newParams = innerClasses.get(externalName) match {
                case Some(entry) if !isScalaRaw && !isStatic(entry.jflags) =>
                  assert(params.head.tpe.typeSymbol == clazz.owner, params.head.tpe.typeSymbol + ": " + clazz.owner)
                  params.tail
                case _ =>
                  params
              }
              info = MethodType(newParams, clazz.tpe)
          }
        sym.setInfo(info)
        setPrivateWithin(sym, jflags)
        parseAttributes(sym, info)
        if ((jflags & JAVA_ACC_VARARGS) != 0) {
          sym.setInfo(arrayToRepeated(sym.info))
        }
        getScope(jflags).enter(sym)
      }
    }
  }

  /** Convert array parameters denoting a repeated parameter of a Java method
   *  to JavaRepeatedParamClass types.
   */
  private def arrayToRepeated(tp: Type): Type = tp match {
    case MethodType(params, rtpe) =>
      val formals = tp.paramTypes
      assert(formals.last.typeSymbol == definitions.ArrayClass)
      val method = params.last.owner
      val elemtp = formals.last.typeArgs.head match {
        case RefinedType(List(t1, t2), _) if (t1.typeSymbol.isAbstractType && t2.typeSymbol == definitions.ObjectClass) =>
          t1 // drop intersection with Object for abstract types in varargs. UnCurry can handle them.
        case t =>
          t
      }
      val newParams = method.newSyntheticValueParams(
        formals.init ::: List(appliedType(definitions.JavaRepeatedParamClass.typeConstructor, List(elemtp))))
      MethodType(newParams, rtpe)
    case PolyType(tparams, rtpe) =>
      PolyType(tparams, arrayToRepeated(rtpe))
  }

  private def sigToType(sym: Symbol, sig: Name): Type = {
    var index = 0
    val end = sig.length
    def accept(ch: Char) {
      assert(sig(index) == ch)
      index += 1
    }
    def subName(isDelimiter: Char => Boolean): Name = {
      val start = index
      while (!isDelimiter(sig(index))) { index += 1 }
      sig.subName(start, index)
    }
    def sig2type(tparams: Map[Name,Symbol], skiptvs: Boolean): Type = {
      val tag = sig(index); index += 1
      tag match {
        case BYTE_TAG   => definitions.ByteClass.tpe
        case CHAR_TAG   => definitions.CharClass.tpe
        case DOUBLE_TAG => definitions.DoubleClass.tpe
        case FLOAT_TAG  => definitions.FloatClass.tpe
        case INT_TAG    => definitions.IntClass.tpe
        case LONG_TAG   => definitions.LongClass.tpe
        case SHORT_TAG  => definitions.ShortClass.tpe
        case VOID_TAG   => definitions.UnitClass.tpe
        case BOOL_TAG   => definitions.BooleanClass.tpe
        case 'L' =>
          def processInner(tp: Type): Type = tp match {
            case TypeRef(pre, sym, args) if (!sym.isStatic) =>
              TypeRef(processInner(pre.widen), sym, args)
            case _ =>
              tp
          }
          def processClassType(tp: Type): Type = tp match {
            case TypeRef(pre, classSym, args) =>
              val existentials = new ListBuffer[Symbol]()
              if (sig(index) == '<') {
                accept('<')
                val xs = new ListBuffer[Type]()
                var i = 0
                while (sig(index) != '>') {
                  sig(index) match {
                    case variance @ ('+' | '-' | '*') =>
                      index += 1
                      val bounds = variance match {
                        case '+' => mkTypeBounds(definitions.NothingClass.tpe,
                                                 sig2type(tparams, skiptvs))
                        case '-' => mkTypeBounds(sig2type(tparams, skiptvs),
                                                 definitions.AnyClass.tpe)
                        case '*' => mkTypeBounds(definitions.NothingClass.tpe,
                                                 definitions.AnyClass.tpe)
                      }
                      val newtparam = sym.newExistential(sym.pos, "?"+i) setInfo bounds
                      existentials += newtparam
                      xs += newtparam.tpe //@M should probably be .tpeHK
                      i += 1
                    case _ =>
                      xs += sig2type(tparams, skiptvs)
                  }
                }
                accept('>')
                assert(xs.length > 0)
                existentialAbstraction(existentials.toList, TypeRef(pre, classSym, xs.toList))
              } else if (classSym.isMonomorphicType) {
                tp
              } else {
                // raw type - existentially quantify all type parameters
                val eparams = typeParamsToExistentials(classSym, classSym.unsafeTypeParams)
                val t = TypeRef(pre, classSym, eparams.map(_.tpe))
                val res = existentialAbstraction(eparams, t)
                if (settings.debug.value && settings.verbose.value) println("raw type " + classSym + " -> " + res)
                res
              }
            case tp =>
              assert(sig(index) != '<')
              tp
          }

          val classSym = classNameToSymbol(subName(c => c == ';' || c == '<'))
          assert(!classSym.hasFlag(OVERLOADED), classSym.alternatives)
          var tpe = processClassType(processInner(classSym.tpe))
          while (sig(index) == '.') {
            accept('.')
            val name = subName(c => c == ';' || c == '<' || c == '.').toTypeName
            val clazz = tpe.member(name)
            tpe = processClassType(processInner(clazz.tpe))
          }
          accept(';')
          tpe
        case ARRAY_TAG =>
          while ('0' <= sig(index) && sig(index) <= '9') index += 1
          var elemtp = sig2type(tparams, skiptvs)
          // make unbounded Array[T] where T is a type variable into Array[T with Object]
          // (this is necessary because such arrays have a representation which is incompatibe
          // with arrays of primitive types.
          if (elemtp.typeSymbol.isAbstractType && !(elemtp <:< definitions.ObjectClass.tpe))
            elemtp = intersectionType(List(elemtp, definitions.ObjectClass.tpe))
          appliedType(definitions.ArrayClass.tpe, List(elemtp))
        case '(' =>
          // we need a method symbol. given in line 486 by calling getType(methodSym, ..)
          assert(sym ne null)
          val paramtypes = new ListBuffer[Type]()
          while (sig(index) != ')') {
            paramtypes += objToAny(sig2type(tparams, skiptvs))
          }
          index += 1
          val restype = if (sym != null && sym.isClassConstructor) {
            accept('V')
            clazz.tpe
          } else
            sig2type(tparams, skiptvs)
          JavaMethodType(sym.newSyntheticValueParams(paramtypes.toList), restype)
        case 'T' =>
          val n = subName(';'.==).toTypeName
          index += 1
          if (skiptvs) definitions.AnyClass.tpe
          else tparams(n).typeConstructor
      }
    } // sig2type(tparams, skiptvs)

    def sig2typeBounds(tparams: Map[Name, Symbol], skiptvs: Boolean): Type = {
      val ts = new ListBuffer[Type]
      while (sig(index) == ':') {
        index += 1
        if (sig(index) != ':') // guard against empty class bound
          ts += objToAny(sig2type(tparams, skiptvs))
      }
      mkTypeBounds(definitions.NothingClass.tpe, intersectionType(ts.toList, sym))
    }

    var tparams = classTParams
    val newTParams = new ListBuffer[Symbol]()
    if (sig(index) == '<') {
      assert(sym != null)
      index += 1
      val start = index
      while (sig(index) != '>') {
        val tpname = subName(':'.==).toTypeName
        val s = sym.newTypeParameter(NoPosition, tpname)
        tparams = tparams + (tpname -> s)
        sig2typeBounds(tparams, true)
        newTParams += s
      }
      index = start
      while (sig(index) != '>') {
        val tpname = subName(':'.==).toTypeName
        val s = tparams(tpname)
        s.setInfo(sig2typeBounds(tparams, false))
      }
      accept('>')
    }
    val ownTypeParams = newTParams.toList
    if (!ownTypeParams.isEmpty)
      sym.setInfo(new TypeParamsType(ownTypeParams))
    val tpe =
      if ((sym eq null) || !sym.isClass)
        sig2type(tparams, false)
      else {
        classTParams = tparams
        val parents = new ListBuffer[Type]()
        while (index < end) {
          parents += sig2type(tparams, false)  // here the variance doesnt'matter
        }
        ClassInfoType(parents.toList, instanceDefs, sym)
      }
    polyType(ownTypeParams, tpe)
  } // sigToType

  class TypeParamsType(override val typeParams: List[Symbol]) extends LazyType {
    override def complete(sym: Symbol) { throw new AssertionError("cyclic type dereferencing") }
  }

  def parseAttributes(sym: Symbol, symtype: Type) {
    def convertTo(c: Constant, pt: Type): Constant = {
      if (pt.typeSymbol == definitions.BooleanClass && c.tag == IntTag)
        Constant(c.value != 0)
      else
        c convertTo pt
    }
    def parseAttribute() {
      val attrName = pool.getName(in.nextChar)
      val attrLen = in.nextInt
      attrName match {
        case nme.SignatureATTR =>
          if (!isScala && !isScalaRaw) {
            val sig = pool.getExternalName(in.nextChar)
            val newType = sigToType(sym, sig)
            sym.setInfo(newType)
            if (settings.debug.value && settings.verbose.value)
              println("" + sym + "; signature = " + sig + " type = " + newType)
            hasMeta = true
          } else
            in.skip(attrLen)
        case nme.SyntheticATTR =>
          sym.setFlag(SYNTHETIC)
          in.skip(attrLen)
        case nme.BridgeATTR =>
          sym.setFlag(BRIDGE)
          in.skip(attrLen)
        case nme.DeprecatedATTR =>
          val arg = Literal(Constant("see corresponding Javadoc for more information."))
          sym.addAnnotation(AnnotationInfo(definitions.DeprecatedAttr.tpe, List(arg), List()))
          in.skip(attrLen)
        case nme.ConstantValueATTR =>
          val c = pool.getConstant(in.nextChar)
          val c1 = convertTo(c, symtype)
          if (c1 ne null) sym.setInfo(mkConstantType(c1))
          else println("failure to convert " + c + " to " + symtype); //debug
        case nme.ScalaSignatureATTR =>
          unpickler.unpickle(in.buf, in.bp, clazz, staticModule, in.file.toString())
          in.skip(attrLen)
          this.isScala = true
        case nme.ScalaATTR =>
          isScalaRaw = true
        case nme.JacoMetaATTR =>
          val meta = pool.getName(in.nextChar).toString().trim()
          metaParser.parse(meta, sym, symtype)
          this.hasMeta = true
         // Attribute on methods of java annotation classes when that method has a default
        case nme.AnnotationDefaultATTR =>
          sym.addAnnotation(AnnotationInfo(definitions.AnnotationDefaultAttr.tpe, List(), List()))
          in.skip(attrLen)
        // Java annotatinos on classes / methods / fields with RetentionPolicy.RUNTIME
        case nme.RuntimeAnnotationATTR =>
          if (!isScala) {
            // no need to read annotations if isScala, ClassfileAnnotations are pickled
            parseAnnotations(attrLen)
            if (settings.debug.value)
              global.inform("" + sym + "; annotations = " + sym.annotations)
          } else
            in.skip(attrLen)

        // TODO 1: parse runtime visible annotations on parameters
        // case nme.RuntimeParamAnnotationATTR

        // TODO 2: also parse RuntimeInvisibleAnnotation / RuntimeInvisibleParamAnnotation,
        // i.e. java annotations with RetentionPolicy.CLASS?

        case nme.ExceptionsATTR if (!isScala) =>
          parseExceptions(attrLen)

        case _ =>
          in.skip(attrLen)
      }
    }

    def parseAnnotArg: Option[ClassfileAnnotArg] = {
      val tag = in.nextByte.toChar
      val index = in.nextChar
      tag match {
        case STRING_TAG =>
          Some(LiteralAnnotArg(Constant(pool.getName(index).toString())))
        case BOOL_TAG | BYTE_TAG | CHAR_TAG | SHORT_TAG | INT_TAG |
             LONG_TAG | FLOAT_TAG | DOUBLE_TAG =>
          Some(LiteralAnnotArg(pool.getConstant(index)))
        case CLASS_TAG  =>
          Some(LiteralAnnotArg(Constant(pool.getType(index))))
        case ENUM_TAG   =>
          val t = pool.getType(index)
          val n = pool.getName(in.nextChar)
          val s = t.typeSymbol.linkedModuleOfClass.info.decls.lookup(n)
          assert(s != NoSymbol, t)
          Some(LiteralAnnotArg(Constant(s)))
        case ARRAY_TAG  =>
          val arr = new ArrayBuffer[ClassfileAnnotArg]()
          var hasError = false
          for (i <- 0 until index)
            parseAnnotArg match {
              case Some(c) => arr += c
              case None => hasError = true
            }
          if (hasError) None
          else Some(ArrayAnnotArg(arr.toArray))
        case ANNOTATION_TAG =>
          parseAnnotation(index) map (NestedAnnotArg(_))
      }
    }

    /** Parse and return a single annotation.  If it is malformed,
     *  return None.
     */
    def parseAnnotation(attrNameIndex: Char): Option[AnnotationInfo] = try {
      val attrType = pool.getType(attrNameIndex)
      val nargs = in.nextChar
      val nvpairs = new ListBuffer[(Name, ClassfileAnnotArg)]
      var hasError = false
      for (i <- 0 until nargs) {
        val name = pool.getName(in.nextChar)
        parseAnnotArg match {
          case Some(c) => nvpairs += ((name, c))
          case None => hasError = true
        }
      }
      if (hasError) None
      else Some(AnnotationInfo(attrType, List(), nvpairs.toList))
    } catch {
      case f: FatalError => throw f // don't eat fatal errors, they mean a class was not found
      case ex: Throwable =>
        if (settings.debug.value)
          global.inform("dropping annotation on " + sym +
                        ", an error occured during parsing (e.g. annotation  class not found)")
        None // ignore malformed annotations ==> t1135
    }

    /**
     * Parse the "Exceptions" attribute which denotes the exceptions
     * thrown by a method.
     */
    def parseExceptions(len: Int) {
      val nClasses = in.nextChar
      for (n <- 0 until nClasses) {
        val cls = pool.getClassSymbol(in.nextChar.toInt)
        sym.addAnnotation(AnnotationInfo(definitions.ThrowsClass.tpe,
                                         Literal(Constant(cls.tpe)) :: Nil,
                                         Nil))
      }
    }

    /** Parse a sequence of annotations and attach them to the
     *  current symbol sym.
     */
    def parseAnnotations(len: Int) {
      val nAttr = in.nextChar
      for (n <- 0 until nAttr)
        parseAnnotation(in.nextChar) match {
          case Some(annot) =>
            sym.addAnnotation(annot)
          case None =>
        }
    }

    // begin parseAttributes
    val attrCount = in.nextChar
    for (i <- 0 until attrCount) parseAttribute()
  }

  /** Enter own inner classes in the right scope. It needs the scopes to be set up,
   *  and implicitly current class' superclasses.
   */
  private def enterOwnInnerClasses {
    def className(name: Name): Name = {
      name.subName(name.lastPos('.') + 1, name.length)
    }

    def enterClassAndModule(entry: InnerClassEntry, completer: global.loaders.SymbolLoader, jflags: Int) {
      val name = entry.originalName
      var sflags = transFlags(jflags, true)

      val innerClass = getOwner(jflags).newClass(NoPosition, name.toTypeName).setInfo(completer).setFlag(sflags)
      val innerModule = getOwner(jflags).newModule(NoPosition, name).setInfo(completer).setFlag(sflags)
      innerModule.moduleClass.setInfo(global.loaders.moduleClassLoader)

      getScope(jflags).enter(innerClass)
      getScope(jflags).enter(innerModule)

      val decls = innerClass.enclosingPackage.info.decls
      val e = decls.lookupEntry(className(entry.externalName))
      if (e ne null) {
        //println("removing " + e)
        decls.unlink(e)
      }
      val e1 = decls.lookupEntry(className(entry.externalName).toTypeName)
      if (e1 ne null) {
        //println("removing " + e1)
        decls.unlink(e1)
      }
    }

    for (entry <- innerClasses.valuesIterator) {
      // create a new class member for immediate inner classes
      if (entry.outerName == externalName) {
        val file = global.classPath.findClass(entry.externalName.toString) match {
          case Some(ClassRep(Some(binary: AbstractFile), _)) => binary
          case _ => throw new AssertionError(entry.externalName)
        }
        enterClassAndModule(entry, new global.loaders.ClassfileLoader(file), entry.jflags)
      }
    }
  }

  /** Parse inner classes. Expects in.bp to point to the superclass entry. Restores the
   *  old bp.
   */
  def parseInnerClasses() {
    val oldbp = in.bp
    skipSuperclasses()
    skipMembers() // fields
    skipMembers() // methods
    val attrs = in.nextChar
    for (i <- 0 until attrs) {
      val attrName = pool.getName(in.nextChar)
      val attrLen = in.nextInt
      attrName match {
        case nme.SignatureATTR =>
          if (!isScala)
            hasMeta = true
          in.skip(attrLen)
        case nme.JacoMetaATTR =>
          this.hasMeta = true
          in.skip(attrLen)
        case nme.ScalaSignatureATTR =>
          isScala = true
          in.skip(attrLen)
        case nme.ScalaATTR =>
          isScalaRaw = true
        case nme.InnerClassesATTR if !isScala =>
          val entries = in.nextChar.toInt
          for (i <- 0 until entries) {
            val innerIndex = in.nextChar.toInt
            val outerIndex = in.nextChar.toInt
            val nameIndex = in.nextChar.toInt
            val jflags = in.nextChar.toInt
            if (innerIndex != 0 && outerIndex != 0 && nameIndex != 0) {
              val entry = InnerClassEntry(innerIndex, outerIndex, nameIndex, jflags)
              innerClasses += (pool.getClassName(innerIndex) -> entry)
            }
          }
        case _ =>
          in.skip(attrLen)
      }
    }
    in.bp = oldbp
  }

  /** An entry in the InnerClasses attribute of this class file. */
  case class InnerClassEntry(external: Int, outer: Int, name: Int, jflags: Int) {
    def externalName: Name = pool.getClassName(external)
    def outerName: Name = pool.getClassName(outer)
    def originalName: Name = pool.getName(name)

    override def toString =
      originalName + " in " + outerName + "(" + externalName +")"
  }

  object innerClasses extends collection.mutable.HashMap[Name, InnerClassEntry] {
    /** Return the Symbol of the top level class enclosing 'name', or 'name's symbol
     *  if no entry found for 'name'.
     */
    def topLevelClass(name: Name): Symbol = {
      val tlName = if (isDefinedAt(name)) {
        var entry = this(name)
        while (isDefinedAt(entry.outerName))
          entry = this(entry.outerName)
        entry.outerName
      } else
        name
      classNameToSymbol(tlName)
    }

    /** Return the class symbol for 'externalName'. It looks it up in its outer class.
     *  Forces all outer class symbols to be completed.
     *
     *  If the given name is not an inner class, it returns the symbol found in 'definitions'.
     */
    def classSymbol(externalName: Name): Symbol = {
      /** Return the symbol of `innerName', having the given `externalName'. */
      def innerSymbol(externalName: Name, innerName: Name, static: Boolean): Symbol = {
        def getMember(sym: Symbol, name: Name): Symbol =
          if (static)
            if (sym == clazz) staticDefs.lookup(name)
            else sym.linkedModuleOfClass.info.member(name)
          else
            if (sym == clazz) instanceDefs.lookup(name)
            else sym.info.member(name)

        innerClasses.get(externalName) match {
          case Some(entry) =>
            val outerName =
              if (entry.outerName.endsWith("$")) entry.outerName.subName(0, entry.outerName.length - 1)
              else entry.outerName
            val sym = classSymbol(outerName)
            val s =
              // if loading during initialization of `definitions' typerPhase is not yet set.
              // in that case we simply load the mmeber at the current phase
              if (currentRun.typerPhase != null)
                atPhase(currentRun.typerPhase)(getMember(sym, innerName.toTypeName))
              else
                getMember(sym, innerName.toTypeName)
            assert(s ne NoSymbol, sym + "." + innerName + " linkedModule: " + sym.linkedModuleOfClass + sym.linkedModuleOfClass.info.members)
            s

          case None =>
            val cls = classNameToSymbol(externalName)
            cls
            //if (static) cls.linkedClassOfModule else cls
        }
      }

      get(externalName) match {
        case Some(entry) =>
          val clazz = innerSymbol(entry.externalName, entry.originalName, isStatic(entry.jflags))
          clazz
        case None =>
          classNameToSymbol(externalName)
      }
    }
  }

  class LazyAliasType(alias: Symbol) extends LazyType {
    override def complete(sym: Symbol) {
      alias.initialize
      val tparams1 = cloneSymbols(alias.typeParams)
      sym.setInfo(polyType(tparams1, alias.tpe.substSym(alias.typeParams, tparams1)))
    }
  }

  def skipAttributes() {
    val attrCount = in.nextChar
    for (i <- 0 until attrCount) {
      in.skip(2); in.skip(in.nextInt)
    }
  }

  def skipMembers() {
    val memberCount = in.nextChar
    for (i <- 0 until memberCount) {
      in.skip(6); skipAttributes()
    }
  }

  def skipSuperclasses() {
    in.skip(2) // superclass
    val ifaces = in.nextChar
    in.skip(2 * ifaces)
  }

  protected def getOwner(flags: Int): Symbol =
    if (isStatic(flags)) statics else clazz

  protected def getScope(flags: Int): Scope =
    if (isStatic(flags)) staticDefs else instanceDefs

  protected def transFlags(flags: Int, isClass: Boolean): Long = {
    var res = 0l
    if ((flags & JAVA_ACC_PRIVATE) != 0)
      res = res | PRIVATE
    else if ((flags & JAVA_ACC_PROTECTED) != 0)
      res = res | PROTECTED
    if ((flags & JAVA_ACC_ABSTRACT) != 0 && (flags & JAVA_ACC_ANNOTATION) == 0)
      res = res | DEFERRED
    if ((flags & JAVA_ACC_FINAL) != 0)
      res = res | FINAL
    if (((flags & JAVA_ACC_INTERFACE) != 0) &&
        ((flags & JAVA_ACC_ANNOTATION) == 0))
      res = res | TRAIT | INTERFACE | ABSTRACT
    if ((flags & JAVA_ACC_SYNTHETIC) != 0)
      res = res | SYNTHETIC
    if ((flags & JAVA_ACC_STATIC) != 0)
      res = res | STATIC
    if (isClass && ((res & DEFERRED) != 0L))
        res = res & ~DEFERRED | ABSTRACT

    res | JAVA
  }

  private def setPrivateWithin(sym: Symbol, jflags: Int) {
    if ((jflags & (JAVA_ACC_PRIVATE | JAVA_ACC_PROTECTED | JAVA_ACC_PUBLIC)) == 0)
      // See ticket #1687 for an example of when topLevelClass is NoSymbol: it
      // apparently occurs when processing v45.3 bytecode.
      if (sym.toplevelClass != NoSymbol)
        sym.privateWithin = sym.toplevelClass.owner
  }

  @inline final private def isPrivate(flags: Int) =
    (flags & JAVA_ACC_PRIVATE) != 0
  @inline final private def isStatic(flags: Int) =
    (flags & JAVA_ACC_STATIC) != 0
  @inline final private def hasAnnotation(flags: Int) =
    (flags & JAVA_ACC_ANNOTATION) != 0
}
