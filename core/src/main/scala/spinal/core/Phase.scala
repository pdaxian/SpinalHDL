package spinal.core

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Created by PIC32F_USER on 05/06/2016.
 */

class PhaseContext(val config : SpinalConfig){
  var globalData = GlobalData.reset
  config.applyToGlobalData(globalData)

  val components = ArrayBuffer[Component]()
  val globalScope = new Scope()
  var topLevel: Component = null
  val enums = mutable.Map[SpinalEnum,mutable.Set[SpinalEnumEncoding]]()
  val reservedKeyWords = mutable.Set[String](
    "in", "out", "buffer", "inout",
    "entity", "component", "architecture",
    "type","open","block","access",
    "or","and","xor","nand","nor",

    "input", "output",
    "module","parameter","logic","reg",
    "begin","end",
    "always","posedge","negedge"
  )

  reservedKeyWords.foreach(globalScope.allocateName(_))

  def sortedComponents = components.sortWith(_.level > _.level)

  def walkNodesDefautStack = {
    val nodeStack = mutable.Stack[Node]()

    topLevel.getAllIo.foreach(nodeStack.push(_))
    components.foreach(c => {
      c match {
        case blackBox: BlackBox => blackBox.getAllIo.filter(_.isInput).foreach(nodeStack.push(_))
        case _ =>
      }
      c.additionalNodesRoot.foreach(nodeStack.push(_))
    })
    nodeStack
  }

  def walkNodesBlackBoxGenerics() = {
    val nodeStack = mutable.Stack[Node]()
    components.foreach(_ match {
      case blackBox: BlackBox => {
        blackBox.getGeneric.flatten.foreach(_ match {
          case bt: BaseType => nodeStack.push(bt)
          case _ =>
        })
      }
      case _ =>
    })
    nodeStack
  }

  def fillNodeConsumer(): Unit = {
    Node.walk(walkNodesDefautStack,(node)=>{
      node.onEachInput(input => {
        if (input != null) input.consumers += node
      })
    })
  }

  def removeNodeConsumer() : Unit = {
    Node.walk(walkNodesDefautStack,_.consumers.clear())
  }

  def fillComponentList(): Unit ={
    components.clear()
    def walk(c: Component): Unit = {
      components += c
      c.children.foreach(walk(_))
    }
    walk(topLevel)
  }

  def checkGlobalData() : Unit = {
    if (!GlobalData.get.clockDomainStack.isEmpty) SpinalWarning("clockDomain stack is not empty :(")
    if (!GlobalData.get.componentStack.isEmpty) SpinalWarning("componentStack stack is not empty :(")
    if (!GlobalData.get.switchStack.isEmpty) SpinalWarning("switchStack stack is not empty :(")
    if (!GlobalData.get.conditionalAssignStack.isEmpty) SpinalWarning("conditionalAssignStack stack is not empty :(")
  }

  def checkPendingErrors() = if(!globalData.pendingErrors.isEmpty) SpinalError()
}

trait Phase{
  def impl(): Unit
}

class MultiPhase(pc: PhaseContext) extends Phase{
  val phases = ArrayBuffer[Phase]()

  override def impl(): Unit = {
    phases.foreach(_.impl())
  }
}


class PhaseFillComponentList(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    pc.fillComponentList()
  }
}



class PhaseApplyIoDefault(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    Node.walk(pc.walkNodesDefautStack,node => {
      node match{
        case node : BaseType => {
          if(node.input == null && node.defaultValue != null){
            val c = node.dir match {
              case `in` => node.component
              case `out` => if(node.component.parent != null)
                node.component.parent
              else
                null
              case _ => node.component
            }
            if(c != null) {
              node.dir match{
                case `in` =>  {
                  Component.push(c.parent)
                  node.assignFrom(node.defaultValue, false)
                  Component.pop(c.parent)
                }
                case _ => {
                  Component.push(c)
                  node.assignFrom(node.defaultValue, false)
                  Component.pop(c)
                }
              }
            }
          }
        }
        case _ =>
      }

    })
  }
}

class PhaseNodesBlackBoxGenerics(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    val nodeStack = mutable.Stack[Node]()
    pc.components.foreach(_ match {
      case blackBox: BlackBox => {
        blackBox.getGeneric.flatten.foreach(_ match {
          case bt: BaseType => nodeStack.push(bt)
          case _ =>
        })
      }
      case _ =>
    })
    nodeStack
  }
}


class PhaseReplaceMemByBlackBox_simplifyWriteReadWithSameAddress(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    class MemTopo(val mem: Mem[_]) {
      val writes = ArrayBuffer[MemWrite]()
      val readsAsync = ArrayBuffer[MemReadAsync]()
      val readsSync = ArrayBuffer[MemReadSync]()
      val writeReadSync = ArrayBuffer[(MemWrite, MemReadSync)]()
      val writeOrReadSync = ArrayBuffer[(MemWriteOrRead_writePart, MemWriteOrRead_readPart)]()
    }
    val memsTopo = mutable.Map[Mem[_], MemTopo]()

    def topoOf(mem: Mem[_]) = memsTopo.getOrElseUpdate(mem, new MemTopo(mem))

    Node.walk(pc.walkNodesDefautStack,node => node match {
      case write: MemWrite => {
        val memTopo = topoOf(write.getMem)
        val readSync = memTopo.readsSync.find(readSync => readSync.originalAddress == write.originalAddress).orNull
        if (readSync == null) {
          memTopo.writes += write
        } else {
          memTopo.readsSync -= readSync
          memTopo.writeReadSync += (write -> readSync)
          readSync.sameAddressThan(write)
        }
      }
      case readAsync: MemReadAsync => topoOf(readAsync.getMem).readsAsync += readAsync
      case readSync: MemReadSync => {
        val memTopo = topoOf(readSync.getMem)
        val write = memTopo.writes.find(write => readSync.originalAddress == write.originalAddress).orNull
        if (write == null) {
          memTopo.readsSync += readSync
        } else {
          memTopo.writes -= write
          memTopo.writeReadSync += (write -> readSync)
          readSync.sameAddressThan(write)
        }
      }
      case writePart: MemWriteOrRead_writePart => {
        val memTopo = topoOf(writePart.getMem)
        if (memTopo.writeOrReadSync.count(_._1 == writePart) == 0) {
          memTopo.writeOrReadSync += (writePart -> writePart.readPart)
        }
      }
      case readPart: MemWriteOrRead_readPart => {
        val memTopo = topoOf(readPart.getMem)
        if (memTopo.writeOrReadSync.count(_._2 == readPart) == 0) {
          memTopo.writeOrReadSync += (readPart.writePart -> readPart)
        }
      }
      case _ =>
    })



    for ((mem, topo) <- memsTopo.iterator if config.forceMemToBlackboxTranslation || mem.forceMemToBlackboxTranslation
         if mem.initialContent == null) {

      if (topo.writes.size == 1 && topo.readsAsync.size == 1 && topo.readsSync.isEmpty && topo.writeReadSync.isEmpty && topo.writeOrReadSync.isEmpty) {
        val wr = topo.writes(0)
        val rd = topo.readsAsync(0)
        val clockDomain = wr.getClockDomain
        clockDomain.push()
        Component.push(mem.component)

        val ram = Component(new Ram_1c_1w_1ra(mem.getWidth, mem.wordCount, rd.writeToReadKind))
        val enable = clockDomain.isClockEnableActive

        ram.io.wr.en := wr.getEnable.allowSimplifyIt() && enable
        ram.io.wr.addr := wr.getAddress.allowSimplifyIt()
        ram.io.wr.data := wr.getData.allowSimplifyIt()

        ram.io.rd.addr := rd.getAddress.allowSimplifyIt()
        rd.getData.allowSimplifyIt() := ram.io.rd.data

        ram.setCompositeName(mem)
        Component.pop(mem.component)
        clockDomain.pop()
      } else if (topo.writes.size == 1 && topo.readsAsync.isEmpty && topo.readsSync.size == 1 && topo.writeReadSync.isEmpty && topo.writeOrReadSync.isEmpty) {
        val wr = topo.writes(0)
        val rd = topo.readsSync(0)
        if (rd.getClockDomain.clock == wr.getClockDomain.clock) {
          val clockDomain = wr.getClockDomain

          clockDomain.push()
          Component.push(mem.component)

          val ram = Component(new Ram_1c_1w_1rs(mem.getWidth, mem.wordCount, rd.writeToReadKind))
          val enable = clockDomain.isClockEnableActive

          ram.io.wr.en := wr.getEnable.allowSimplifyIt() && enable
          ram.io.wr.addr := wr.getAddress.allowSimplifyIt()
          ram.io.wr.data := wr.getData.allowSimplifyIt()

          ram.io.rd.en := rd.getReadEnable.allowSimplifyIt() && enable
          ram.io.rd.addr := rd.getAddress.allowSimplifyIt()
          rd.getData.allowSimplifyIt() := ram.io.rd.data

          ram.generic.useReadEnable = {
            val lit = ram.io.rd.en.getLiteral[BoolLiteral]
            lit == null || lit.value == false
          }

          ram.setCompositeName(mem)
          Component.pop(mem.component)
          clockDomain.pop()
        }
      } else if (topo.writes.isEmpty && topo.readsAsync.isEmpty && topo.readsSync.isEmpty && topo.writeReadSync.size == 1 && topo.writeOrReadSync.isEmpty) {
        val wr = topo.writeReadSync(0)._1
        val rd = topo.writeReadSync(0)._2
        if (rd.getClockDomain.clock == wr.getClockDomain.clock) {
          val clockDomain = wr.getClockDomain

          clockDomain.push()
          Component.push(mem.component)

          val ram = Component(new Ram_1wrs(mem.getWidth, mem.wordCount, rd.writeToReadKind))
          val enable = clockDomain.isClockEnableActive

          ram.io.addr := wr.getAddress.allowSimplifyIt()
          ram.io.wr.en := wr.getEnable.allowSimplifyIt() && enable
          ram.io.wr.data := wr.getData.allowSimplifyIt()

          ram.io.rd.en := rd.getReadEnable.allowSimplifyIt() && enable
          rd.getData.allowSimplifyIt() := ram.io.rd.data

          ram.generic.useReadEnable = {
            val lit = ram.io.rd.en.getLiteral[BoolLiteral]
            lit == null || lit.value == false
          }

          ram.setCompositeName(mem)
          Component.pop(mem.component)
          clockDomain.pop()
        }
      } else if (topo.writes.isEmpty && topo.readsAsync.isEmpty && topo.readsSync.isEmpty && topo.writeReadSync.isEmpty && topo.writeOrReadSync.size == 1) {
        val wr = topo.writeOrReadSync(0)._1
        val rd = topo.writeOrReadSync(0)._2
        if (rd.getClockDomain.clock == wr.getClockDomain.clock) {
          val clockDomain = wr.getClockDomain

          clockDomain.push()
          Component.push(mem.component)

          val ram = Component(new Ram_1wors(mem.getWidth, mem.wordCount, rd.writeToReadKind))
          val enable = clockDomain.isClockEnableActive

          ram.io.addr := wr.getAddress.allowSimplifyIt()
          ram.io.cs := wr.getChipSelect.allowSimplifyIt() && enable
          ram.io.we := wr.getWriteEnable.allowSimplifyIt()
          ram.io.wrData := wr.getData.allowSimplifyIt()

          rd.getData.allowSimplifyIt() := ram.io.rdData

          ram.setCompositeName(mem)
          Component.pop(mem.component)
          clockDomain.pop()
        }
      }
    }
    pc.fillComponentList()
  }
}

class PhaseNameNodesByReflection(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    globalData.nodeAreNamed = true
    if (topLevel.getName() == null) topLevel.setWeakName("toplevel")
    for (c <- sortedComponents) {
      c.nameElements()
      if(c.definitionName == null)
        c.definitionName = c.getClass.getSimpleName
      c match {
        case bb: BlackBox => {
          bb.getGeneric.genNames
        }
        case _ =>
      }
    }
  }
}

class PhaseCollectAndNameEnum(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    Node.walk(walkNodesDefautStack,node => {
      node match {
        case enum: SpinalEnumCraft[_] => enums.getOrElseUpdate(enum.blueprint,mutable.Set[SpinalEnumEncoding]()).add(enum.encoding)
        case _ =>
      }
    })

    for (enumDef <- enums.keys) {
      Misc.reflect(enumDef, (name, obj) => {
        obj match {
          case obj: Nameable => obj.setWeakName(name)
          case _ =>
        }
      })
      for (e <- enumDef.values) {
        if (e.isUnnamed) {
          e.setWeakName("s" + e.position)
        }
      }
      if (enumDef.isWeak) {
        var name = enumDef.getClass.getSimpleName
        if (name.endsWith("$"))
          name = name.substring(0, name.length - 1)
        enumDef.setWeakName(name)
      }
    }
  }
}

class PhasePullClockDomains(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    Node.walk(walkNodesDefautStack,(node, push) =>  {
      node match {
        case delay: SyncNode => {
          if(delay.isUsingResetSignal && !delay.getClockDomain.hasResetSignal)
            SpinalError(s"Clock domain without reset contain a register which needs one\n ${delay.getScalaLocationLong}")

          Component.push(delay.component)
          delay.setInput(SyncNode.getClockInputId,delay.getClockDomain.readClockWire)

          if(delay.isUsingResetSignal)  delay.setInput(SyncNode.getClockResetId,delay.getClockDomain.readResetWire)
          if(delay.isUsingEnableSignal) delay.setInput(SyncNode.getClockEnableId,delay.getClockDomain.readClockEnableWire)
          Component.pop(delay.component)
        }
        case _ =>
      }
      node.onEachInput(push(_))
    })
  }
}

class PhaseCheck_noNull_noCrossHierarchy_noInputRegister_noDirectionLessIo(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    val errors = mutable.ArrayBuffer[String]()

    for(c <- components){
      try{
        val io = c.reflectIo
        for(bt <- io.flatten){
          if(bt.isDirectionLess){
            errors += s"Direction less signal into io def ${bt.getScalaLocationLong}"
          }
        }
      }catch{
        case _ : Throwable =>
      }

    }
    if(!errors.isEmpty)
      SpinalError(errors)

    Node.walk(walkNodesDefautStack,node => {
      node match {
        case node: BaseType => {
          val nodeInput0 = node.input
          if (nodeInput0 != null) {
            if (node.isInput && nodeInput0.isInstanceOf[Reg] && nodeInput0.component == node.component) {
              errors += s"Input register are not allowed \n${node.getScalaLocationLong}"
            } else {
              val nodeInput0IsIo = nodeInput0.isInstanceOf[BaseType] && nodeInput0.asInstanceOf[BaseType].isIo
              if (node.isIo) {
                if (node.isInput) {
                  if (nodeInput0.component != node.component.parent && !(!nodeInput0.component.isTopLevel && nodeInput0IsIo && nodeInput0.component.parent == node.component.parent)) {
                    if (nodeInput0.component == node.component)
                      errors += s"Input $node can't be assigned from inside at\n${ScalaLocated.long(node.assignementThrowable)}"
                    else
                      errors += s"Input $node is not assigned by parent component but another at\n${ScalaLocated.long(node.assignementThrowable)}"
                  }
                } else if (node.isOutput) {
                  if (nodeInput0.component != node.component && !(nodeInput0IsIo && node.component == nodeInput0.component.parent))
                    errors += s"Output $node is not assigned by his component but an other at\n${ScalaLocated.long(node.assignementThrowable)}"
                } else
                  errors += s"No direction specified on IO \n${node.getScalaLocationLong}"
              } else {
                if (nodeInput0.component != node.component && !(nodeInput0IsIo && node.component == nodeInput0.component.parent))
                  errors += s"Node $node is assigned outside his component at\n${ScalaLocated.long(node.assignementThrowable)}"
              }
            }
          } else {
            if (!(node.isInput && node.component.isTopLevel) && !(node.isOutput && node.component.isInstanceOf[BlackBox]))
              errors += s"No driver on $node at \n${node.getScalaLocationLong}"
          }
        }
        case _ => {
          node.onEachInput((in,idx) => {
            if (in == null) {
              errors += s"No driver on ${node.getScalaLocationLong}"
            } else {
              if (in.component != node.component && !(in.isInstanceOf[BaseType] && in.asInstanceOf[BaseType].isIo && node.component == in.component.parent)) {
                val throwable = node match{
                  case node : AssignementTreePart => node.getAssignementContext(idx)
                  case _ => node.scalaTrace
                }
                errors += s"Node is driven outside his component \n${ScalaLocated.long(throwable)}"
              }
            }
          })
        }
      }
    })
    if (!errors.isEmpty)
      SpinalError(errors)
  }

}

class PhaseAddInOutBinding(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    Node.walk(walkNodesDefautStack,(node,push) => {
      //Create inputss bindings, usefull if the node is driven by when statments
      if (node.isInstanceOf[BaseType] && node.component.parent != null) {
        val baseType = node.asInstanceOf[BaseType]
        if (baseType.isInput) {
          val inBinding = baseType.clone //To be sure that there is no need of resize between it and node
          inBinding.assignementThrowable = baseType.assignementThrowable
          inBinding.scalaTrace = baseType.scalaTrace
          inBinding.input = baseType.input
          baseType.input = inBinding
          inBinding.component = node.component.parent
          inBinding.dontCareAboutNameForSymplify = true
        }
      }

      node.onEachInput(push(_))

      //Create outputs bindings
      node.onEachInput((nodeInput,i) => {
        val nodeInput = node.getInput(i)
        nodeInput match {
          case nodeInput: BaseType => {
            if (nodeInput.isOutput && (nodeInput.component.parent == node.component || (nodeInput.component.parent == node.component.parent && nodeInput.component != node.component))) {
              val into = nodeInput.component.parent
              val bind = into.kindsOutputsToBindings.getOrElseUpdate(nodeInput, {
                val bind = nodeInput.clone
                bind.scalaTrace = nodeInput.scalaTrace
                bind.assignementThrowable = nodeInput.assignementThrowable
                into.kindsOutputsToBindings.put(nodeInput, bind)
                into.kindsOutputsBindings += bind
                bind.component = into
                bind.input = nodeInput
                bind.dontCareAboutNameForSymplify = true
                bind
              })

              node.setInput(i,bind)
            }
          }
          case _ =>
        }
      })
    })
  }
}

class PhaseNameBinding(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
  import pc._
    for (c <- components) {
      for ((bindedOut, bind) <- c.kindsOutputsToBindings) {
        if (bind.isUnnamed && bindedOut.component.isNamed && bindedOut.isNamed) {
          bind.setWeakName(bindedOut.component.getName() + "_" + bindedOut.getName())
        }
      }
    }

    Node.walk(walkNodesDefautStack,node => node match {
      case node: BaseType => {
        if (node.isInput && node.input != null && node.input.isInstanceOf[Nameable]) {
          val nameable = node.input.asInstanceOf[Nameable]
          if (nameable.isUnnamed && node.component.isNamed && node.isNamed) {
            nameable.setWeakName(node.component.getName() + "_" + node.getName())
          }
        }
      }
      case _ =>
    })
  }
}

class PhaseAllowNodesToReadOutputs(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    val outputsBuffers = mutable.Map[BaseType, BaseType]()
    Node.walk(walkNodesDefautStack,node => {
      node.onEachInput((nodeInput,i) => {
        nodeInput match {
          case baseTypeInput: BaseType => {
            if (baseTypeInput.isOutput && baseTypeInput.component.parent != node.component) {
              val buffer = outputsBuffers.getOrElseUpdate(baseTypeInput, {
                val buffer = baseTypeInput.clone()
                buffer.input = baseTypeInput.input
                baseTypeInput.input = buffer
                buffer.component = baseTypeInput.component
                buffer
              })
              node.setInput(i,buffer)
            }
          }
          case _ =>
        }
      })
    })
  }
}

class PhaseAllowNodesToReadInputOfKindComponent(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    Node.walk(walkNodesDefautStack,node => {
      node.onEachInput((input,i) => {
        input match {
          case baseTypeInput: BaseType => {
            if (baseTypeInput.isInput && baseTypeInput.component.parent == node.component) {
              node.setInput(i,baseTypeInput.input)
            }
          }
          case _ =>
        }
      })
    })
  }
}

class PhasePostWidthInferationChecks(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    val errors = mutable.ArrayBuffer[String]()
    Node.walk(walkNodesDefautStack ++ walkNodesBlackBoxGenerics,_ match {
      case node : Reg =>{
        if(node.initialValue == null && node.dataInput == node){
          errors += s"$node has no assignement value and no reset value at\n ${node.getScalaLocationLong}"
        }
      }
      case _ =>
    })
    if(!errors.isEmpty)
      SpinalError(errors)
  }
}

class PhaseInferWidth(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    globalData.nodeAreInferringWidth = true
    val nodes = ArrayBuffer[Node]()
    Node.walk(walkNodesDefautStack ++ walkNodesBlackBoxGenerics,nodes += _)


    def checkAll(): Unit = {
      val errors = mutable.ArrayBuffer[String]()
      for (node <- nodes) {
        if (node.inferWidth && !node.isInstanceOf[Reg]) {
          //Don't care about Reg width inference
          errors += s"Can't infer width on ${node.getScalaLocationLong}"
        }
        if (node.widthWhenNotInferred != -1 && node.widthWhenNotInferred != node.getWidth) {
          errors += s"getWidth call result during elaboration differ from inferred width on ${node.getScalaLocationLong}"
        }
      }
      if (errors.nonEmpty)
        SpinalError(errors)
    }

    var iterationCounter = 0
    while (true) {
      iterationCounter = iterationCounter + 1
      var somethingChange = false
      for (node <- nodes) {
        val hasChange = node.inferWidth
        somethingChange = somethingChange || hasChange
      }

      if (!somethingChange || iterationCounter == nodes.size) {
        checkAll()
        return
      }
    }
  }
}

class PhaseSimplifyNodes(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    fillNodeConsumer
    Node.walk(walkNodesDefautStack,_.simplifyNode)
    removeNodeConsumer
  }
}

class PhasePropagateBaseTypeWidth(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    Node.walk(walkNodesDefautStack,node => {
      node match {
        case node: BaseType => {
          val width = node.getWidth

          node.input match {
            case that: Reg => {
              that.inferredWidth = width
              if(that.initialValue != null) walk(that,RegS.getInitialValueId)
              walk(that,RegS.getDataInputId)
            }
            case _ => walk(node,0)
          }
          walk(node,0)

          def walk(parent: Node,inputId : Int): Unit = {
            val that = parent.getInput(inputId)
            def walkChildren() : Unit = that.onEachInput((input,id) => walk(that,id))

            that match {
              case that: Multiplexer => { //TODO probably useless
                that.inferredWidth = width
                walk(that,1)
                walk(that,2)
              }
              case that: WhenNode => {
                that.inferredWidth = width
                walk(that,1)
                walk(that,2)
              }
              case that: MultipleAssignmentNode => {
                that.inferredWidth = width
                walkChildren()
              }
              case that : AssignementNode => that.inferredWidth = width
              case that: CaseNode => {
                that.inferredWidth = width
                walkChildren()
              }
              case that: SwitchNode => {
                that.inferredWidth = width
                walkChildren()
              }
              case dontCare : DontCareNode =>{
                dontCare.inferredWidth = width
              }
              // case lit : BitsAllToLiteral => lit.inferredWidth = width
              case bitVector : BitVector  => {
                if(bitVector.getWidth < width  && ! bitVector.isReg) {
                  val default = bitVector.spinalTags.find(_.isInstanceOf[TagDefault]).getOrElse(null).asInstanceOf[TagDefault]

                  if (default != null) {
                    val addedBitCount = width - bitVector.getWidth
                    Component.push(bitVector.component)
                    val newOne = bitVector.weakClone
                    newOne.inferredWidth = width
                    if(bitVector.getWidth > 0)
                      newOne(bitVector.getWidth-1,0) := bitVector
                    default.default match {
                      case (_,value : Boolean) =>  {
                        val lit = default.litFacto(if(value) (BigInt(1) << addedBitCount)-1 else 0,addedBitCount)
                        newOne(width-1,bitVector.getWidth).assignFrom(lit,false)
                      }
                      case (_,value : Bool) =>{
                        for(i <- bitVector.getWidth until width)
                          newOne(i) := value
                      }
                    }

                    parent.setInput(inputId,newOne)
                    Component.pop(bitVector.component)
                  }
                }

              }
              case _ =>
            }
          }

        }
        case _ =>
      }
    })

  }
}

class PhaseNormalizeNodeInputs(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    Node.walk(walkNodesDefautStack,(node,push) => {
      node.onEachInput(push(_))
      node.normalizeInputs
    })
  }
}

class PhaseCheckInferredWidth(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    val errors = mutable.ArrayBuffer[String]()
    Node.walk(walkNodesDefautStack,node => {
      val error = node.checkInferedWidth
      if (error != null)
        errors += error
    })

    if (errors.nonEmpty)
      SpinalError(errors)
  }
}

class PhaseCheckCombinationalLoops(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    val targetAlgoId = GlobalData.get.allocateAlgoId()

    val errors = mutable.ArrayBuffer[String]()
    val pendingNodes = mutable.Stack[Node]()
    pendingNodes.pushAll(walkNodesDefautStack)

    def nodeIsCompleted(node: Node) = node.algoId = targetAlgoId
    def isNodeCompleted(node : Node) = node.algoId == targetAlgoId

    while (!pendingNodes.isEmpty) {
      val pop = pendingNodes.pop()
      walk(scala.collection.immutable.HashMap[Node, AssignedBits](),Nil,pop,pop.getWidth-1,0)
    }

    if (!errors.isEmpty)
      SpinalError(errors)

    def walk(consumers :  scala.collection.immutable.HashMap[Node, AssignedBits],stack : List[(Node,Int,Int)],
             node: Node,
             outHi : Int, outLo : Int): Unit = {
      if (node == null || node.component == null || node.isInstanceOf[NoneNode]) {

      }else {
        val newStack = Tuple3(node,outHi,outLo) :: stack
        var bitsAlreadyUsed = consumers.getOrElse(node, new AssignedBits(node.getWidth))
        if (bitsAlreadyUsed.isIntersecting(AssignedRange(outHi, outLo))) {
          val ordred = newStack.reverseIterator
          val filtred = ordred.dropWhile((e) => (e._1 != node || e._2 < outLo || e._3 > outHi)).drop(1).toArray
          // val filtredNode = filtred.map(_._1)

          val wellNameLoop = filtred.reverseIterator.filter{case (n,hi,lo) => n.isInstanceOf[Nameable] && n.asInstanceOf[Nameable].isNamed}.map{case (n,hi,lo)  => n.asInstanceOf[Nameable].toString() + s"[$hi:$lo]"}.foldLeft("")(_ + _ + " ->\n      ")
          val multiLineLoop = filtred.reverseIterator.map(n => "      " + n.toString).reduceLeft(_ + "\n" + _)
          errors += s"  Combinatorial loop !\n      Partial chain :\n      ${wellNameLoop}\n      Full chain :\n${multiLineLoop}"
        }else if (!isNodeCompleted(node)) {
          node match {
            case syncNode: SyncNode => {
              nodeIsCompleted(node)
              val newConsumers = consumers + (node -> bitsAlreadyUsed.+(AssignedRange(outHi, outLo)))
              val syncNode = node.asInstanceOf[SyncNode]
              syncNode.getSynchronousInputs.foreach(addPendingNode(_))
              syncNode.getAsynchronousInputs.foreach(i => walk(newConsumers,newStack, i, i.getWidth - 1, 0)) //TODO, pessimistic
            }
            case baseType: BaseType => {
              val consumersPlusFull = consumers + (baseType -> bitsAlreadyUsed.+(AssignedRange(node.getWidth - 1, 0)))
              def walkBaseType(node: Node): Unit = {
                if (node != null) {
                  node match {
                    case node: MultipleAssignmentNode => node.onEachInput(input => walkBaseType(input))
                    case node: WhenNode => {
                      walk(consumersPlusFull,newStack, node.cond, 0, 0) //Todo, to pessimistic !
                      walkBaseType(node.whenTrue)
                      walkBaseType(node.whenFalse)
                    }
                    case node: AssignementNode => {
                      val newConsumers = consumers + (baseType -> bitsAlreadyUsed.+(node.getScopeBits))
                      node.onEachInput((input,idx) => {
                        val (inHi, inLo) = node.getOutToInUsage(idx, outHi, outLo)
                        if (inHi >= inLo) walk(newConsumers,newStack, input, inHi, inLo)
                      })
                    }
                    case _ => {
                      walk(consumersPlusFull,newStack, node, outHi, outLo)
                    }
                  }
                }
              }

              walkBaseType(baseType.input)
            }
            case _ => {
              val newConsumers = consumers + (node -> bitsAlreadyUsed.+(AssignedRange(outHi, outLo)))
              node.onEachInput((input,idx) => {
                if (input != null) {
                  val (inHi, inLo) = node.getOutToInUsage(idx, outHi, outLo)
                  if (inHi >= inLo) walk(newConsumers,newStack, input, inHi, inLo)
                }
              })
            }
          }
          if (outHi == node.getWidth - 1 && outLo == 0) nodeIsCompleted(node)
        }
      }
    }
    def addPendingNode(node: Node) = {
      if (node != null && ! isNodeCompleted(node)) pendingNodes.push(node)
    }

  }
}

class PhaseCheckCrossClockDomains(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    val errors = mutable.ArrayBuffer[String]()

    Node.walk(walkNodesDefautStack,node => {
      node match {
        case syncNode: SyncNode => {
          if (!syncNode.hasTag(crossClockDomain)) {
            val consumerCockDomain = syncNode.getClockDomain
            for (syncInput <- syncNode.getSynchronousInputs) {
              val walked = mutable.Set[Object]() //TODO upgrade it to the check bit by bit
              check(syncInput)
              def check(that: Node): Unit = {
                if(walked.contains(that)) return;
                walked += that
                if(that == null){
                  println(":(")
                }
                if (!that.hasTag(crossClockDomain)) {
                  that match {
                    case syncDriver: SyncNode => {
                      val driverClockDomain = syncDriver.getClockDomain
                      if (//syncDriver.getClockDomain.clock != consumerCockDomain.clock &&
                        ! driverClockDomain.isSyncronousWith(consumerCockDomain)) {
                        errors += s"Synchronous element ${syncNode.getScalaLocationShort} is driven " +
                          s"by ${syncDriver.getScalaLocationShort} but they don't have the same clock domain. " +
                          s"Register declaration at \n${syncNode.getScalaLocationLong}"
                      }
                    }
                    case _ => that.onEachInput(input => if (input != null) check(input))
                  }
                }
              }
            }
          }
        }
        case _ =>
      }
    })

    if (!errors.isEmpty)
      SpinalError(errors)
  }
}

class PhaseFillNodesConsumers(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    pc.fillNodeConsumer()
  }
}



class PhaseDontSymplifyBasetypeWithComplexAssignement(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    Node.walk(walkNodesDefautStack,node => {
      node match {
        case baseType: BaseType => {
          baseType.input match {
            case wn: WhenNode => baseType.dontSimplifyIt()
            case an: AssignementNode => baseType.dontSimplifyIt()
            case man: MultipleAssignmentNode => baseType.dontSimplifyIt()
            case _ =>
          }
        }
        case _ =>
      }
    })
  }
}



class PhaseDeleteUselessBaseTypes(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    Node.walk(walkNodesDefautStack,(node, push) => {
      node match {
        case node: BaseType => {
          if ((node.isUnnamed || node.dontCareAboutNameForSymplify) && !node.isIo && node.consumers.size == 1 && node.canSymplifyIt) {
            val consumer = node.consumers(0)
            val input = node.input
            if (!node.isDelay || consumer.isInstanceOf[BaseType]) {
              // don't allow to put a non base type on component inputss
              if (input.isInstanceOf[BaseType] || !consumer.isInstanceOf[BaseType] || !consumer.asInstanceOf[BaseType].isInput) {
                //don't allow to jump from kind to kind
                val isKindOutputBinding = node.component.kindsOutputsBindings.contains(node)
                if (!(isKindOutputBinding && (!consumer.isInstanceOf[BaseType] || node.component == consumer.component.parent))) {

                  val inputConsumer = input.consumers

                  if (isKindOutputBinding) {
                    val newBind = consumer.asInstanceOf[BaseType]
                    node.component.kindsOutputsBindings += newBind
                    node.component.kindsOutputsToBindings += (input.asInstanceOf[BaseType] -> newBind)
                  }
                  consumer.onEachInput((consumerInput,idx) => {
                    if (consumerInput == node)
                      consumer.setInput(idx,input)
                  })
                  inputConsumer -= node
                  inputConsumer += consumer
                }
              }
            }
          }
        }

        case _ =>
      }
      node.onEachInput(push(_))
    })
  }
}

class PhaseCheck_noAsyncNodeWithIncompleteAssignment(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    val errors = mutable.ArrayBuffer[String]()

    Node.walk(walkNodesDefautStack,node => node match {
      case signal: BaseType if !signal.isDelay => {

        val signalRange = new AssignedRange(signal.getWidth - 1, 0)

        def walk(nodes: Iterator[Node]): AssignedBits = {
          val assignedBits = new AssignedBits(signal.getBitsWidth)

          for (node <- nodes) node match {
            case wn: WhenNode => {
              assignedBits.add(AssignedBits.intersect(walk(Iterator(wn.whenTrue)), walk(Iterator(wn.whenFalse))))
            }
            case an: AssignementNode => {
              assignedBits.add(an.getAssignedBits)
            }
            case man: MultipleAssignmentNode => return walk(man.getInputs)
            case nothing: NoneNode =>
            case _ => assignedBits.add(signalRange)
          }
          assignedBits
        }

        val assignedBits = walk(signal.getInputs)

        val unassignedBits = new AssignedBits(signal.getBitsWidth)
        unassignedBits.add(signalRange)
        unassignedBits.remove(assignedBits)
        if (!unassignedBits.isEmpty)
          errors += s"Incomplete assignment is detected on $signal, unassigned bit mask " +
            s"is ${unassignedBits.toBinaryString}, declared at\n${signal.getScalaLocationLong}"
      }
      case _ =>
    })

    if (errors.nonEmpty)
      SpinalError(errors)
  }
}

class PhaseSimplifyBlacBoxGenerics(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    components.foreach(_ match {
      case blackBox: BlackBox => {
        blackBox.getGeneric.flatten.foreach(tuple => {
          val signal = tuple
          if (signal.isInstanceOf[BaseType]) {
            val baseType = signal.asInstanceOf[BaseType]
            walk(baseType, baseType)
            def walk(node: Node, first: Node): Unit = node match {
              case node: BaseType => {
                first.setInput(0,node.input)
                first.getInput(0).inferredWidth = first.inferredWidth
                walk(node.input, first)
              }
              case lit: Literal =>
              case _ => throw new Exception("BlackBox generic must be literal")
            }
          }

        })
      }
      case _ =>
    })
  }
}

class PhasePrintUnUsedSignals(prunedSignals : mutable.Set[BaseType])(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._

    val targetAlgoId = GlobalData.get.algoId
    Node.walk(walkNodesDefautStack,node => {node.algoId = targetAlgoId})

    for(c <- components){
      def checkNameable(that : Any) : Unit = that match {
        case area : Area => {
          area.forEachNameables(obj => checkNameable(obj))
        }
        case data : Data =>  {
          data.flatten.foreach(bt => {
            if(bt.algoId != targetAlgoId && bt.getWidth != 0 && !bt.hasTag(unusedTag)){
              prunedSignals += bt
            }
          })
        }
        case _ => {}
      }

      c.forEachNameables(obj => checkNameable(obj))
    }
    if(!prunedSignals.isEmpty){
      SpinalWarning(s"${prunedSignals.size} signals were pruned. You can call printPruned on the backend report to get more informations.")
    }
  }
}

class PhaseAddNodesIntoComponent(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    Node.walk({
      val stack = walkNodesDefautStack
      for (c <- components) {
        c.nodes = ArrayBuffer[Node]()
      }
      stack
    },node => {
      node.component.nodes += node
    })
  }
}

class PhaseOrderComponentsNodes(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    for (c <- components) {
      c.nodes = c.nodes.sortWith(_.instanceCounter < _.instanceCounter)
    }
  }
}

class PhaseAllocateNames(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    for (enumDef <- enums.keys) {
      if (enumDef.isWeak)
        enumDef.setName(globalScope.allocateName(enumDef.getName()));
      else
        globalScope.iWantIt(enumDef.getName())
    }
    for (c <- sortedComponents) {
      reservedKeyWords.foreach(c.localScope.allocateName(_))
      c.allocateNames

      if (c.isInstanceOf[BlackBox])
        globalScope.lockName(c.definitionName)
      else
        c.definitionName = globalScope.allocateName(c.definitionName)
    }
  }
}

class PhaseRemoveComponentThatNeedNoHdlEmit(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    val componentsFiltred = components.filter(c => {
      if (c.isInBlackBoxTree) {
        false
      } else if (c.nodes.size == 0) {
        if (c.parent != null) c.parent.children -= c
        false
      } else {
        true
      }
    })
    components.clear()
    components ++= componentsFiltred
  }
}

class PhasePrintStates(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    var counter = 0
    Node.walk(walkNodesDefautStack,_ => counter = counter + 1)
    SpinalInfo(s"Graph has $counter nodes")
  }
}

class PhaseCreateComponent(gen : => Component)(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    import pc._
    val defaultClockDomain = ClockDomain.external("",frequency = config.defaultClockDomainFrequency)
    ClockDomain.push(defaultClockDomain)
    pc.topLevel = gen
    ClockDomain.pop(defaultClockDomain)

    pc.checkGlobalData()
  }
}

class PhaseDummy(doThat : => Unit) extends Phase{
  override def impl(): Unit = {
    doThat
  }
}

object SpinalVhdlBoot{
  def apply[T <: Component](config : SpinalConfig)(gen : => T) : SpinalReport[T] ={
    try {
      singleShot(config)(gen)
    } catch {
      case e: Throwable => {
        if(!config.debug){
          Thread.sleep(100)
          println("\n**********************************************************************************************")
          val errCnt = SpinalError.getErrorCount()
          SpinalWarning(s"Elaboration failed (${errCnt} error" + (if(errCnt > 1){s"s"} else {s""}) + s").\n" +
            s"          Spinal will restart with scala trace to help you to find the problem.")
          println("**********************************************************************************************\n")
          Thread.sleep(100)
          return singleShot(config.copy(debug = true))(gen)
        }else{
          Thread.sleep(100)
          println("\n**********************************************************************************************")
          val errCnt = SpinalError.getErrorCount()
          SpinalWarning(s"Elaboration failed (${errCnt} error" + (if(errCnt > 1){s"s"} else {s""}) + ").")
          println("**********************************************************************************************")
          Thread.sleep(100)
          throw e
        }
      }
    }
  }

  def singleShot[T <: Component](config : SpinalConfig)(gen : => T): SpinalReport[T] ={
    val pc = new PhaseContext(config)
    val prunedSignals = mutable.Set[BaseType]()



    SpinalInfoPhase("Start elaboration")


    val phases = ArrayBuffer[Phase]()

    phases += new PhaseCreateComponent(gen)(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Start analysis and transform"))
    phases += new PhaseFillComponentList(pc)
    phases += new PhaseApplyIoDefault(pc)
    phases += new PhaseNodesBlackBoxGenerics(pc)
    phases += new PhaseReplaceMemByBlackBox_simplifyWriteReadWithSameAddress(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Get names from reflection"))
    phases += new PhaseNameNodesByReflection(pc)
    phases += new PhaseCollectAndNameEnum(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Transform connections"))
    phases += new PhasePullClockDomains(pc)
    phases += new PhaseCheck_noNull_noCrossHierarchy_noInputRegister_noDirectionLessIo(pc)
    phases += new PhaseAddInOutBinding(pc)
    phases += new PhaseNameBinding(pc)
    phases += new PhaseAllowNodesToReadOutputs(pc)
    phases += new PhaseAllowNodesToReadInputOfKindComponent(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Infer nodes's bit width"))
    phases += new PhasePostWidthInferationChecks(pc)
    phases += new PhaseInferWidth(pc)
    phases += new PhaseSimplifyNodes(pc)
    phases += new PhaseInferWidth(pc)
    phases += new PhasePropagateBaseTypeWidth(pc)
    phases += new PhaseNormalizeNodeInputs(pc)
    phases += new PhaseCheckInferredWidth(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Check combinatorial loops"))
    phases += new PhaseCheckCombinationalLoops(pc)
    phases += new PhaseDummy(SpinalInfoPhase("Check cross clock domains"))
    phases += new PhaseCheckCrossClockDomains(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Simplify graph's nodes"))
    phases += new PhaseFillNodesConsumers(pc)
    phases += new PhaseDontSymplifyBasetypeWithComplexAssignement(pc)
    phases += new PhaseDeleteUselessBaseTypes(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Check that there is no incomplete assignment"))
    phases += new PhaseCheck_noAsyncNodeWithIncompleteAssignment(pc)
    phases += new PhaseSimplifyBlacBoxGenerics(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Collect signals not used in the graph"))
    phases += new PhasePrintUnUsedSignals(prunedSignals)(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Finalise"))
    phases += new PhaseAddNodesIntoComponent(pc)
    phases += new PhaseOrderComponentsNodes(pc)
    phases += new PhaseAllocateNames(pc)
    phases += new PhaseRemoveComponentThatNeedNoHdlEmit(pc)

    phases += new PhasePrintStates(pc)



    phases += new PhaseVhdl(pc)
    phases += new VhdlTestBenchBackend(pc)




    for(phase <- phases){
      phase.impl()
      pc.checkPendingErrors()
    }


    pc.checkGlobalData()

    val report = new SpinalReport[T](pc.topLevel.asInstanceOf[T])
    report.prunedSignals ++= prunedSignals

    report
  }
}



class PhaseDontSymplifyVerilogMismatchingWidth(pc: PhaseContext) extends Phase{
  override def impl(): Unit = {
    def applyTo(that : Node): Unit ={
      assert(that.consumers.size == 1)
      that.consumers(0).asInstanceOf[BaseType].dontSimplifyIt()
    }
    import pc._
    Node.walk(walkNodesDefautStack,node => {
      node match {
        case node: Resize => applyTo(node)
        case node: Modifier => applyTo(node) // .....
//        case node: Operator.BitVector.Add => applyTo(node)
//        case node: Operator.BitVector.Sub => applyTo(node)
//        case node: Operator.BitVector.ShiftRightByInt => applyTo(node)
//        case node: Operator.Bits.Cat => applyTo(node)
        case node : Extract => applyTo(node)
        case _ =>
      }
    })
  }
}




object SpinalVerilogBoot{
  def apply[T <: Component](config : SpinalConfig)(gen : => T) : SpinalReport[T] ={
    try {
      singleShot(config)(gen)
    } catch {
      case e: Throwable => {
        if(!config.debug){
          Thread.sleep(100)
          println("\n**********************************************************************************************")
          val errCnt = SpinalError.getErrorCount()
          SpinalWarning(s"Elaboration failed (${errCnt} error" + (if(errCnt > 1){s"s"} else {s""}) + s").\n" +
            s"          Spinal will restart with scala trace to help you to find the problem.")
          println("**********************************************************************************************\n")
          Thread.sleep(100)
          return singleShot(config.copy(debug = true))(gen)
        }else{
          Thread.sleep(100)
          println("\n**********************************************************************************************")
          val errCnt = SpinalError.getErrorCount()
          SpinalWarning(s"Elaboration failed (${errCnt} error" + (if(errCnt > 1){s"s"} else {s""}) + ").")
          println("**********************************************************************************************")
          Thread.sleep(100)
          throw e
        }
      }
    }
  }

  def singleShot[T <: Component](config : SpinalConfig)(gen : => T): SpinalReport[T] ={
    val pc = new PhaseContext(config)
    val prunedSignals = mutable.Set[BaseType]()

    SpinalInfoPhase("Start elaboration")


    val phases = ArrayBuffer[Phase]()

    phases += new PhaseCreateComponent(gen)(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Start analysis and transform"))
    phases += new PhaseFillComponentList(pc)
    phases += new PhaseApplyIoDefault(pc)
    phases += new PhaseNodesBlackBoxGenerics(pc)
    phases += new PhaseReplaceMemByBlackBox_simplifyWriteReadWithSameAddress(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Get names from reflection"))
    phases += new PhaseNameNodesByReflection(pc)
    phases += new PhaseCollectAndNameEnum(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Transform connections"))
    phases += new PhasePullClockDomains(pc)
    phases += new PhaseCheck_noNull_noCrossHierarchy_noInputRegister_noDirectionLessIo(pc)
    phases += new PhaseAddInOutBinding(pc)
    phases += new PhaseNameBinding(pc)
    phases += new PhaseAllowNodesToReadOutputs(pc)
    phases += new PhaseAllowNodesToReadInputOfKindComponent(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Infer nodes's bit width"))
    phases += new PhasePostWidthInferationChecks(pc)
    phases += new PhaseInferWidth(pc)
    phases += new PhaseSimplifyNodes(pc)
    phases += new PhaseInferWidth(pc)
    phases += new PhasePropagateBaseTypeWidth(pc)
    phases += new PhaseNormalizeNodeInputs(pc)
    phases += new PhaseCheckInferredWidth(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Check combinatorial loops"))
    phases += new PhaseCheckCombinationalLoops(pc)
    phases += new PhaseDummy(SpinalInfoPhase("Check cross clock domains"))
    phases += new PhaseCheckCrossClockDomains(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Simplify graph's nodes"))
    phases += new PhaseFillNodesConsumers(pc)
    phases += new PhaseDontSymplifyBasetypeWithComplexAssignement(pc)
    phases += new PhaseDontSymplifyVerilogMismatchingWidth(pc)    //VERILOG
    phases += new PhaseDeleteUselessBaseTypes(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Check that there is no incomplete assignment"))
    phases += new PhaseCheck_noAsyncNodeWithIncompleteAssignment(pc)
    phases += new PhaseSimplifyBlacBoxGenerics(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Collect signals not used in the graph"))
    phases += new PhasePrintUnUsedSignals(prunedSignals)(pc)

    phases += new PhaseDummy(SpinalInfoPhase("Finalise"))
    phases += new PhaseAddNodesIntoComponent(pc)
    phases += new PhaseOrderComponentsNodes(pc)
    phases += new PhaseAllocateNames(pc)
    phases += new PhaseRemoveComponentThatNeedNoHdlEmit(pc)

    phases += new PhasePrintStates(pc)



    phases += new PhaseVerilog(pc)





    for(phase <- phases){
      phase.impl()
      pc.checkPendingErrors()
    }


    pc.checkGlobalData()

    val report = new SpinalReport[T](pc.topLevel.asInstanceOf[T])
    report.prunedSignals ++= prunedSignals

    report
  }
}