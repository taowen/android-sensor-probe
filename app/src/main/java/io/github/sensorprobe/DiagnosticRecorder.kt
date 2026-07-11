package io.github.sensorprobe

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.DataInputStream
import java.io.EOFException
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One diagnostic session per selected USB device. Raw USB is written by JNI;
 * this class owns the human-readable timeline, decoded values and final ZIP. */
class DiagnosticRecorder(private val context:Context):Closeable {
    private var sessionDir:File?=null
    private var timeline:BufferedWriter?=null
    private var readings:BufferedWriter?=null
    private var tcpRaw:FileOutputStream?=null
    private var startedWall:Instant?=null
    private var device:UsbDevice?=null
    private val decoded=AtomicLong();private val rawPackets=AtomicLong();private val decodeFailures=AtomicLong()
    @Volatile var lastReport:File?=null;private set
    val active get()=sessionDir!=null
    val nativeTracePath get()=sessionDir?.resolve("raw/usb-transfers.bin")?.absolutePath

    @Synchronized fun begin(d:UsbDevice):File {
        if(active)finish()
        val stamp=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss",Locale.US).format(java.time.ZonedDateTime.now())
        val dir=File(context.getExternalFilesDir(null),"reports/session-$stamp-${d.vendorId.toString(16)}-${d.productId.toString(16)}")
        File(dir,"raw").mkdirs();File(dir,"decoded").mkdirs();File(dir,"logs").mkdirs()
        sessionDir=dir;device=d;startedWall=Instant.now();decoded.set(0);rawPackets.set(0);decodeFailures.set(0)
        timeline=BufferedWriter(FileWriter(File(dir,"timeline.jsonl")))
        readings=BufferedWriter(FileWriter(File(dir,"decoded/imu.csv"))).apply {
            write("host_time_ns,source,device_timestamp,timestamp_unit,ax,ay,az,gx,gy,gz,mx,my,mz,temperature,proximity,ambient_light,raw_hex\n")
        }
        tcpRaw=FileOutputStream(File(dir,"raw/tcp-stream.bin"))
        File(dir,"device.json").writeText(deviceJson(d).toString(2))
        File(dir,"README.txt").writeText("Sensor Probe community diagnostic report. USB serial is hashed; camera images are not recorded. usb-transfers.bin uses SPTR v1 records documented in report.json.\n")
        event("session","begin",mapOf("model" to ModelCatalog.identify(d).displayName))
        return dir
    }

    @Synchronized fun event(category:String,name:String,values:Map<String,Any?> = emptyMap()) {
        val out=timeline?:return
        val json=JSONObject().put("host_time_ns",SystemClock.elapsedRealtimeNanos()).put("wall_time",Instant.now().toString()).put("category",category).put("event",name)
        values.forEach{(k,v)->json.put(k,v?:JSONObject.NULL)}
        out.write(json.toString());out.newLine();out.flush()
    }

    @Synchronized fun usbPacket(interfaceId:Int,endpoint:Int,direction:String,status:Int,payload:ByteArray,length:Int) {
        val count=rawPackets.incrementAndGet()
        if(count<=4 || count%1000L==0L)event("usb_kotlin","packet_counter",mapOf("count" to count,"interface" to interfaceId,"endpoint" to endpoint,"direction" to direction,"status" to status,"last_length" to length))
    }

    @Synchronized fun reading(r:SensorReading,hostNs:Long=SystemClock.elapsedRealtimeNanos()) {
        decoded.incrementAndGet();val out=readings?:return
        fun q(s:String)=buildString{append('"');s.forEach{if(it=='"')append("\"\"")else append(it)};append('"')}
        fun v(a:FloatArray?,i:Int)=a?.getOrNull(i)?.toString().orEmpty()
        out.write(listOf(hostNs,q(r.source),r.timestamp?:"",r.timestampUnit,v(r.accel,0),v(r.accel,1),v(r.accel,2),v(r.gyro,0),v(r.gyro,1),v(r.gyro,2),v(r.magnet,0),v(r.magnet,1),v(r.magnet,2),r.temperature?:"",r.proximity?:"",r.ambientLight?:"",q(r.rawHex)).joinToString(","));out.newLine()
        if(decoded.get()%50L==0L)out.flush()
    }

    fun decodeFailure(protocol:String,reason:String,length:Int,head:String){decodeFailures.incrementAndGet();event("decode","rejected",mapOf("protocol" to protocol,"reason" to reason,"length" to length,"head" to head))}
    @Synchronized fun tcpChunk(bytes:ByteArray,length:Int){tcpRaw?.write(bytes,0,length);event("tcp","chunk",mapOf("length" to length))}
    fun cameraFrame(kind:String,hostNs:Long,deviceTimestamp:Long?,encodedSize:Int,decoded:Boolean)=event("camera","frame",mapOf("kind" to kind,"host_time_ns_capture" to hostNs,"device_timestamp" to deviceTimestamp,"encoded_size" to encodedSize,"decoded" to decoded))

    @Synchronized fun finish():File? {
        val dir=sessionDir?:return lastReport
        event("session","end",mapOf("decoded" to decoded.get(),"raw_packets_kotlin" to rawPackets.get(),"decode_failures" to decodeFailures.get()))
        timeline?.close();readings?.close();tcpRaw?.close();timeline=null;readings=null;tcpRaw=null
        File(context.getExternalFilesDir(null),"sensor-probe-readings.log").takeIf{it.isFile}?.copyTo(File(dir,"logs/app.log"),overwrite=true)
        runCatching { ProcessBuilder("logcat","-d","--pid=${android.os.Process.myPid()}","-v","threadtime").start().inputStream.use{input->File(dir,"logs/logcat.txt").outputStream().use(input::copyTo)} }
        val nativeStats=analyzeNativeTrace(File(dir,"raw/usb-transfers.bin"))
        val report=JSONObject().put("format","sensor-probe-report-v1").put("started",startedWall.toString()).put("finished",Instant.now().toString())
            .put("decoded_readings",decoded.get()).put("kotlin_raw_packets",rawPackets.get()).put("decode_failures",decodeFailures.get())
            .put("native_trace",nativeStats)
            .put("native_trace_format",JSONObject().put("magic","SPTR").put("version",1).put("record","magic:u32 version:u16 kind:u8 direction:u8 host_ns:u64 sequence:u64 interface:i16 endpoint:i16 status:i32 requested:u32 actual:u32 payload[actual]")
                .put("kinds",JSONObject(mapOf("11" to "interrupt_submit","12" to "interrupt_complete","21" to "bulk_submit","22" to "bulk_complete","31" to "control_submit","32" to "control_complete","41" to "async_submit","42" to "async_callback","43" to "async_resubmit","44" to "async_cancel_request","51" to "claim_complete","52" to "release_complete"))))
        File(dir,"report.json").writeText(report.toString(2))
        val zip=File(context.getExternalFilesDir(null),"sensor-probe-report-${dir.name.removePrefix("session-")}.zip")
        ZipOutputStream(FileOutputStream(zip)).use{z->dir.walkTopDown().filter{it.isFile}.forEach{f->z.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath));f.inputStream().use{it.copyTo(z)};z.closeEntry()}}
        lastReport=zip;sessionDir=null;device=null;return zip
    }
    override fun close(){finish()}

    private fun analyzeNativeTrace(file:File):JSONObject {
        val endpoints=mutableMapOf<String,Long>();var records=0L;var payloadBytes=0L;var errors=0L
        if(!file.isFile)return JSONObject().put("present",false)
        fun DataInputStream.u16le()=readUnsignedByte() or (readUnsignedByte() shl 8)
        fun DataInputStream.i32le():Int {val a=readUnsignedByte();val b=readUnsignedByte();val c=readUnsignedByte();val d=readUnsignedByte();return a or (b shl 8) or (c shl 16) or (d shl 24)}
        fun DataInputStream.u32le()=i32le().toLong() and 0xffffffffL
        fun DataInputStream.skipExact(n:Long){var left=n;while(left>0){val done=skip(left);if(done<=0)throw EOFException();left-=done}}
        runCatching { DataInputStream(file.inputStream().buffered()).use{input->while(true){
            if(input.u32le()!=0x52545053L)break;input.u16le();val kind=input.readUnsignedByte();val direction=input.readUnsignedByte();input.skipExact(16);val intf=input.u16le().toShort().toInt();val ep=input.u16le().toShort().toInt();val status=input.i32le();input.u32le();val actual=input.u32le();input.skipExact(actual)
            records++;payloadBytes+=actual;if(status!=0)errors++;val key="$intf:${"0x%02x".format(ep and 255)}:${if(direction==1)"in" else "out"}:kind$kind";endpoints[key]=(endpoints[key]?:0)+1
        } } }
        return JSONObject().put("present",true).put("file_bytes",file.length()).put("records",records).put("payload_bytes",payloadBytes).put("nonzero_status_records",errors).put("endpoints",JSONObject(endpoints as Map<*,*>))
    }

    private fun deviceJson(d:UsbDevice):JSONObject {
        val model=ModelCatalog.identify(d);val interfaces=JSONArray()
        for(i in 0 until d.interfaceCount){val f=d.getInterface(i);val eps=JSONArray();for(j in 0 until f.endpointCount){val e=f.getEndpoint(j);eps.put(JSONObject().put("address",e.address).put("direction",if(e.direction==UsbConstants.USB_DIR_IN)"in" else "out").put("type",e.type).put("max_packet_size",e.maxPacketSize).put("interval",e.interval))};interfaces.put(JSONObject().put("id",f.id).put("class",f.interfaceClass).put("subclass",f.interfaceSubclass).put("protocol",f.interfaceProtocol).put("endpoints",eps))}
        val serial=runCatching{d.serialNumber}.getOrNull()?.let{MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(6).joinToString(""){b->"%02x".format(b)}}
        val appVersion=runCatching{context.packageManager.getPackageInfo(context.packageName,0).versionName}.getOrNull()
        return JSONObject().put("brand",model.brand).put("identified_model",model.model).put("capabilities",model.capabilities).put("vid",d.vendorId).put("pid",d.productId).put("manufacturer",d.manufacturerName).put("product",d.productName).put("serial_sha256_prefix",serial).put("device_class",d.deviceClass).put("device_subclass",d.deviceSubclass).put("device_protocol",d.deviceProtocol).put("interfaces",interfaces).put("phone",JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT).put("abis",JSONArray(Build.SUPPORTED_ABIS.toList())).put("app_version",appVersion))
    }
}
