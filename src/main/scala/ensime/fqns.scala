// Copyright: Sam Halliday
// License: GPLv3+
package ensime

sealed trait DescriptorType {
  def descriptorString: String
}
case class Descriptor(params: List[DescriptorType], ret: DescriptorType) {
  def descriptorString =
    "(" + params.map(_.descriptorString).mkString("") + ")" + ret.descriptorString
}
case class ArrayDescriptor(fqn: DescriptorType) extends DescriptorType {
  def descriptorString = "[" + fqn.descriptorString
}

sealed trait FullyQualifiedName {
  def internalString: String
  def className: ClassName
}

case class ClassName(pkg: List[String], name: String)
    extends FullyQualifiedName with DescriptorType {

  override def className: ClassName = this

  private def nonPrimitiveInternalString: String =
    "L" + (if (pkg.isEmpty) name
           else pkg.mkString("/") + "/" + name) + ";"

  override def descriptorString: String = internalString
  override def internalString: String = {
    if (pkg.isEmpty)
      name match {
        case "boolean" => "Z"
        case "byte"    => "B"
        case "char"    => "C"
        case "short"   => "S"
        case "int"     => "I"
        case "long"    => "J"
        case "float"   => "F"
        case "double"  => "D"
        case "void"    => "V"
        case _         => nonPrimitiveInternalString
      } else nonPrimitiveInternalString
  }

  private def nonPrimitiveScalaString: String =
    (if (pkg.isEmpty) name else pkg.mkString("", ".", "." + name))
      .stripSuffix("$")
      .replace("$", ".")

  def scalaString: String =  {
    if (pkg.isEmpty)
      name match {
        case "boolean" => "Boolean"
        case "byte"    => "Byte"
        case "char"    => "Char"
        case "short"   => "Short"
        case "int"     => "Int"
        case "long"    => "Long"
        case "float"   => "Float"
        case "double"  => "Double"
        case "void"    => "Unit"
        case _         => nonPrimitiveScalaString
      } else nonPrimitiveScalaString
  }

  def normalise: ClassName = ClassName.normaliseClass(this)
}

object ClassName {
  private def Primitive(name: String): ClassName = ClassName(Nil, name)

  val PrimitiveBoolean = Primitive("boolean")
  val PrimitiveByte    = Primitive("byte")
  val PrimitiveChar    = Primitive("char")
  val PrimitiveShort   = Primitive("short")
  val PrimitiveInt     = Primitive("int")
  val PrimitiveLong    = Primitive("long")
  val PrimitiveFloat   = Primitive("float")
  val PrimitiveDouble  = Primitive("double")
  val PrimitiveVoid    = Primitive("void")

  val ScalaArray = ClassName(List("scala"), "Array")

  def fromInternal(internal: String): ClassName = {
    val parts           = internal.split("/")
    val (before, after) = parts.splitAt(parts.length - 1)
    ClassName(before.toList, after(0))
  }

  private val ScalaPackageName: List[String] = List("scala")
  private val normaliseClass: ClassName => ClassName = Map(
    ClassName(List("scala", "runtime"), "BoxedUnit") -> PrimitiveVoid,
    ClassName(ScalaPackageName, "<byname>") -> ClassName(ScalaPackageName, "Function0"),
    ClassName(ScalaPackageName, "Boolean") -> PrimitiveBoolean,
    ClassName(ScalaPackageName, "Byte") -> PrimitiveByte,
    ClassName(ScalaPackageName, "Char") -> PrimitiveChar,
    ClassName(ScalaPackageName, "Short") -> PrimitiveShort,
    ClassName(ScalaPackageName, "Int") -> PrimitiveInt,
    ClassName(ScalaPackageName, "Long") -> PrimitiveLong,
    ClassName(ScalaPackageName, "Float") -> PrimitiveFloat,
    ClassName(ScalaPackageName, "Double") -> PrimitiveDouble,
    ClassName(ScalaPackageName, "Void") -> PrimitiveVoid
  ).withDefault(identity)

}

sealed trait MemberName extends FullyQualifiedName

case class FieldName(
  owner: ClassName,
  name: String
) extends MemberName {
  override def className: ClassName = owner
  override def internalString = owner.internalString + "." + name
}

case class MethodName(
  owner: ClassName,
  name: String,
  descriptor: String
) extends MemberName {
  override def className: ClassName = owner
  override def internalString = owner.internalString + "." + name + descriptor
}
