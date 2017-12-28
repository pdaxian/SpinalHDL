package spinal.sim

import java.io.File
import javax.tools.JavaFileObject

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import sys.process._



class VerilatorBackendConfig{
  var signals = ArrayBuffer[Signal]()
  var optimisationLevel : Int = 2
  val rtlSourcesPaths = ArrayBuffer[String]()
  var toplevelName: String = null
  var workspacePath: String = null
  var workspaceName: String = null
  var vcdPath: String = null
  var vcdPrefix: String = null
  var withWave = true
  var waveDepth = 1 // 0 => all
}

object VerilatorBackend{
  private var uniqueId = 0
  def allocateUniqueId() : Int = {
    this.synchronized {
      uniqueId = uniqueId + 1
      uniqueId
    }
  }
}

class VerilatorBackend(val config : VerilatorBackendConfig) {
  val isWindows = System.getProperty("os.name").startsWith("Windows")
  val uniqueId = VerilatorBackend.allocateUniqueId()
  val workspaceName = config.workspaceName
  val workspacePath = config.workspacePath
  val wrapperCppName = s"V${config.toplevelName}__spinalWrapper.cpp"
  val wrapperCppPath = new File(s"${workspacePath}/${workspaceName}/$wrapperCppName").getAbsolutePath

  def patchPath(path : String) : String = {
    if(isWindows){
      var tmp = path
      if(tmp.substring(1,2) == ":") tmp = "/" + tmp.substring(0,1).toLowerCase + tmp.substring(2)
      tmp = tmp.replace("//", "////")
      tmp
    }else{
      path
    }
  }
  
  def clean(): Unit ={
    s"rm -rf ${workspacePath}/${workspaceName}".!
//    s"rm ${workspacePath}/libV${config.toplevelName}.so".!
  }

  def genWrapperCpp(): Unit = {
    val jniPrefix = "Java_" + s"wrapper_${workspaceName}".replace("_", "_1") + "_VerilatorNative_"
    val wrapperString = s"""
#include <stdint.h>
#include <string>
#include <jni.h>

#include "V${config.toplevelName}.h"
#include "verilated_vcd_c.h"

class ISignalAccess{
public:
  virtual void getAU8(JNIEnv *env, jbyteArray value) {}
  virtual void setAU8(JNIEnv *env, jbyteArray value, int length) {}

  virtual uint64_t getU64() = 0;
  virtual void setU64(uint64_t value) = 0;
};

class  CDataSignalAccess : public ISignalAccess{
public:
    CData *raw;
    CDataSignalAccess(CData *raw) : raw(raw){

    }
    uint64_t getU64() {return *raw;}
    void setU64(uint64_t value)  {*raw = value; }
};


class  SDataSignalAccess : public ISignalAccess{
public:
    SData *raw;
    SDataSignalAccess(SData *raw) : raw(raw){

    }
    uint64_t getU64() {return *raw;}
    void setU64(uint64_t value)  {*raw = value; }
};


class  IDataSignalAccess : public ISignalAccess{
public:
    IData *raw;
    IDataSignalAccess(IData *raw) : raw(raw){

    }
    uint64_t getU64() {return *raw;}
    void setU64(uint64_t value)  {*raw = value; }
};


class  QDataSignalAccess : public ISignalAccess{
public:
    QData *raw;
    QDataSignalAccess(QData *raw) : raw(raw){

    }
    uint64_t getU64() {return *raw;}
    void setU64(uint64_t value)  {*raw = value; }
};



class  WDataSignalAccess : public ISignalAccess{
public:
    WData *raw;
    uint32_t width;
    bool sint;

    WDataSignalAccess(WData *raw, uint32_t width, bool sint) : raw(raw), width(width), sint(sint){

    }

    uint64_t getU64() {return raw[0] + (((uint64_t)raw[1]) << 32);}
    void setU64(uint64_t value)  {
      uint32_t wordsCount = (width+31)/32;
      raw[0] = value;
      raw[1] = value >> 32;
      uint32_t padding = (value & 0x8000000000000000) && sint ? 0xFFFFFFFFFFFFFFFF : 0;
      for(uint32_t idx = 2;idx < wordsCount;idx++){
        raw[idx] = padding;
      }

      if(width%32 != 0) raw[wordsCount-1] &= (1l << width%32)-1;
    }

    void getAU8(JNIEnv *env, jbyteArray value) {
      uint32_t wordsCount = (width+31)/32;
      uint32_t byteCount = wordsCount*4;
      uint32_t shift = 32-(width % 32);
      uint32_t backup = raw[wordsCount-1];
      uint8_t values[byteCount + !sint];
      if(sint && shift != 32) raw[wordsCount-1] = (((int32_t)backup) << shift) >> shift;
      for(uint32_t idx = 0;idx < byteCount;idx++){
        values[idx + !sint] = ((uint8_t*)raw)[byteCount-idx-1];
      }
      (env)->SetByteArrayRegion ( value, 0, byteCount + !sint, reinterpret_cast<jbyte*>(values));
      raw[wordsCount-1] = backup;
    }

    void setAU8(JNIEnv *env, jbyteArray jvalue, int length) {
      jbyte value[length];
      (env)->GetByteArrayRegion( jvalue, 0, length, value);
      uint32_t wordsCount = (width+31)/32;
      uint32_t padding = (value[0] & 0x80 && sint) != 0 ? 0xFFFFFFFF : 0;
      for(uint32_t idx = 0;idx < wordsCount;idx++){
        raw[idx] = padding;
      }
      uint32_t capedLength = length > 4*wordsCount ? 4*wordsCount : length;
      for(uint32_t idx = 0;idx < capedLength;idx++){
        ((uint8_t*)raw)[idx] = value[length-idx-1];
      }
      if(width%32 != 0) raw[wordsCount-1] &= (1l << width%32)-1;
    }
};

class Wrapper_${uniqueId}{
public:
    uint64_t time;
    V${config.toplevelName} top;
    ISignalAccess *signalAccess[${config.signals.length}];
    #ifdef TRACE
	  VerilatedVcdC tfp;
	  #endif

    Wrapper_${uniqueId}(const char * name){
      time = 0;
${val signalInits = for((signal, id) <- config.signals.zipWithIndex)
      yield s"      signalAccess[$id] = new ${if(signal.dataType.width <= 8) "CData"
      else if(signal.dataType.width <= 16) "SData"
      else if(signal.dataType.width <= 32) "IData"
      else if(signal.dataType.width <= 64) "QData"
      else "WData"}SignalAccess(${if(signal.dataType.width <= 64)"&" else ""}top.${signal.path.mkString(".")}${if(signal.dataType.width > 64) s", ${signal.dataType.width}, ${if(signal.dataType.isInstanceOf[SIntDataType]) "true" else "false"}" else ""});\n"
  signalInits.mkString("")}
      #ifdef TRACE
      Verilated::traceEverOn(true);
      top.trace(&tfp, 99);
      tfp.open((std::string("${new File(config.vcdPath).getAbsolutePath.replace("\\","\\\\")}/${if(config.vcdPrefix != null) config.vcdPrefix + "_" else ""}") + name + ".vcd").c_str());
      #endif
    }

    virtual ~Wrapper_${uniqueId}(){
      for(int idx = 0;idx < ${config.signals.length};idx++){
          delete signalAccess[idx];
      }

      #ifdef TRACE
      tfp.dump(time);
      tfp.close();
      tfp.dump(time);
      #endif
    }

};


#ifdef __cplusplus
extern "C" {
#endif
#include <stdio.h>
#include <stdint.h>


JNIEXPORT Wrapper_${uniqueId} * JNICALL ${jniPrefix}newHandle_1${uniqueId}
  (JNIEnv * env, jobject obj, jstring name, jint seedValue){
    #if defined(_WIN32) && !defined(__CYGWIN__)
    srand(seedValue);
    #else
    srand48(seedValue);
    #endif
    Verilated::randReset(2);
    const char* ch = env->GetStringUTFChars(name, 0);
    Wrapper_${uniqueId} *handle = new Wrapper_${uniqueId}(ch);
    env->ReleaseStringUTFChars(name, ch);
    return handle;
}

JNIEXPORT void JNICALL ${jniPrefix}eval_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} *handle){
   handle->top.eval();
}


JNIEXPORT void JNICALL ${jniPrefix}sleep_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} *handle, uint64_t cycles){
  #ifdef TRACE
  handle->tfp.dump(handle->time);
  #endif
  handle->time += cycles;
}

JNIEXPORT jlong JNICALL ${jniPrefix}getU64_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} *handle, int id){
  return handle->signalAccess[id]->getU64();
}

JNIEXPORT void JNICALL ${jniPrefix}setU64_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} *handle, int id, uint64_t value){
  handle->signalAccess[id]->setU64(value);
}

JNIEXPORT void JNICALL ${jniPrefix}deleteHandle_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} * handle){
  delete handle;
}

JNIEXPORT void JNICALL ${jniPrefix}getAU8_1${uniqueId}
  (JNIEnv * env, jobject obj, Wrapper_${uniqueId} * handle, jint id, jbyteArray value){
  handle->signalAccess[id]->getAU8(env, value);
}



JNIEXPORT void JNICALL ${jniPrefix}setAU8_1${uniqueId}
  (JNIEnv * env, jobject obj, Wrapper_${uniqueId} * handle, jint id, jbyteArray value, jint length){
  handle->signalAccess[id]->setAU8(env, value, length);
}



#ifdef __cplusplus
}
#endif
     """
    val outFile = new java.io.FileWriter(wrapperCppPath)
    outFile.write(wrapperString)
    outFile.flush()
    outFile.close()

    val exportMapString =
      s"""CODEABI_1.0 {
         |    global: $jniPrefix*;
         |    local: *;
         |};""".stripMargin

    val exportmapFile = new java.io.FileWriter(s"${workspacePath}/${workspaceName}/libcode.version")
    exportmapFile.write(exportMapString)
    exportmapFile.flush()
    exportmapFile.close()
  }

  class Logger extends ProcessLogger {override def err(s: => String): Unit = {if(!s.startsWith("ar: creating ")) println(s)}
    override def out(s: => String): Unit = {}
    override def buffer[T](f: => T) = f
  }

  def compileVerilator(): Unit = {
    // VL_THREADED
    val jdk = System.getProperty("java.home").replace("/jre","").replace("\\jre","")
    assert(!jdk.contains(" "), s"""Your JDK path contains spaces : ($jdk), If you are on windows, you can workaround it by : mklink /j "C:\\pf" "C:\\Program Files"  and then setting your JDK path via C:\\pf""")
    val flags = List("-fPIC", "-m64", "-shared")
    val verolatorCmd = s"""${if(isWindows)"verilator_bin.exe" else "verilator"}
       | ${flags.map("-CFLAGS " + _).mkString(" ")}
       | ${flags.map("-LDFLAGS " + _).mkString(" ")}
       | -CFLAGS -I$jdk/include -CFLAGS -I$jdk/include/${if(isWindows)"win32" else "linux"}
       | -LDFLAGS '-Wl,--version-script=libcode.version'
       | -Wno-WIDTH -Wno-UNOPTFLAT
       | --x-assign unique
       | --trace-depth ${config.waveDepth}
       | -CFLAGS -O${config.optimisationLevel}
       | ${if(config.withWave) "-CFLAGS -DTRACE --trace" else ""}
       | --Mdir ${workspaceName}
       | --top-module ${config.toplevelName}
       | -cc ${ "../../" + new File(config.rtlSourcesPaths.head).toString.replace("\\","/")}
       | --exe $workspaceName/$wrapperCppName""".stripMargin.replace("\n", "")
    Process(verolatorCmd, new File(workspacePath)).! (new Logger())
    genWrapperCpp()
    s"make -j2 -C ${workspacePath}/${workspaceName} -f V${config.toplevelName}.mk V${config.toplevelName}".!  (new Logger())
    s"cp ${workspacePath}/${workspaceName}/V${config.toplevelName}.exe ${workspacePath}/${workspaceName}/${workspaceName}_$uniqueId.${if(isWindows) "dll" else "so"}".! (new Logger())
  }

  def compileJava() : Unit = {
    val verilatorNativeImplCode =
      s"""package wrapper_${workspaceName};
         |import spinal.sim.IVerilatorNative;
         |
         |public class VerilatorNative implements IVerilatorNative {
         |    public long newHandle(String name, int seed) { return newHandle_${uniqueId}(name, seed);}
         |    public void eval(long handle) { eval_${uniqueId}(handle);}
         |    public void sleep(long handle, long cycles) { sleep_${uniqueId}(handle, cycles);}
         |    public long getU64(long handle, int id) { return getU64_${uniqueId}(handle, id);}
         |    public void setU64(long handle, int id, long value) { setU64_${uniqueId}(handle, id, value);}
         |    public void getAU8(long handle, int id, byte[] value) { getAU8_${uniqueId}(handle, id, value);}
         |    public void setAU8(long handle, int id, byte[] value, int length) { setAU8_${uniqueId}(handle, id, value, length);}
         |    public void deleteHandle(long handle) { deleteHandle_${uniqueId}(handle);}
         |
         |    public native long newHandle_${uniqueId}(String name, int seed);
         |    public native void eval_${uniqueId}(long handle);
         |    public native void sleep_${uniqueId}(long handle, long cycles);
         |    public native long getU64_${uniqueId}(long handle, int id);
         |    public native void setU64_${uniqueId}(long handle, int id, long value);
         |    public native void getAU8_${uniqueId}(long handle, int id, byte[] value);
         |    public native void setAU8_${uniqueId}(long handle, int id, byte[] value, int length);
         |    public native void deleteHandle_${uniqueId}(long handle);
         |
         |    static{
         |      System.load("${new File(s"${workspacePath}/${workspaceName}").getAbsolutePath.replace("\\","\\\\")}/${workspaceName}_$uniqueId.${if(isWindows) "dll" else "so"}");
         |    }
         |}
       """.stripMargin

    val verilatorNativeImplFile = new DynamicCompiler.InMemoryJavaFileObject(s"wrapper_${workspaceName}.VerilatorNative", verilatorNativeImplCode)
    import collection.JavaConverters._
    DynamicCompiler.compile(List[JavaFileObject](verilatorNativeImplFile).asJava, s"${workspacePath}/${workspaceName}")
  }

  def checks(): Unit ={
    if(System.getProperty("java.class.path").contains("sbt-launch.jar")){
      System.err.println("""[Error] It look like you are running the simulation with SBT without having the SBT 'fork := true' configuration.\n  Add it in the build.sbt file to fix this issue, see https://github.com/SpinalHDL/SpinalTemplateSbt/blob/master/build.sbt""")
      throw new Exception()
    }
  }

  clean()
  checks()
  compileVerilator()
  compileJava()

  val nativeImpl = DynamicCompiler.getClass(s"wrapper_${workspaceName}.VerilatorNative", s"${workspacePath}/${workspaceName}")
  val nativeInstance : IVerilatorNative = nativeImpl.newInstance().asInstanceOf[IVerilatorNative]

  def instanciate(name : String, seed : Int) = nativeInstance.newHandle(name, seed)
}

