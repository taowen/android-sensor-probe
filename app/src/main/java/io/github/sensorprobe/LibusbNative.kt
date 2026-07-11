package io.github.sensorprobe

object LibusbNative {
    init { System.loadLibrary("sensorprobe_native") }
    external fun open(fd:Int):Long
    external fun claim(handle:Long,interfaceId:Int):Int
    external fun release(handle:Long,interfaceId:Int):Int
    external fun interruptTransfer(handle:Long,endpoint:Int,data:ByteArray,length:Int,timeout:Int):Int
    external fun bulkTransfer(handle:Long,endpoint:Int,data:ByteArray,length:Int,timeout:Int):Int
    external fun controlTransfer(handle:Long,type:Int,request:Int,value:Int,index:Int,data:ByteArray,length:Int,timeout:Int):Int
    external fun startEndpointReader(handle:Long,endpoint:Int,transferType:Int,packetSize:Int):Int
    external fun readEndpoint(handle:Long,endpoint:Int,data:ByteArray,timeout:Int):Int
    external fun startXrealHelenReceiver(handle:Long):String
    external fun readXrealHelen(handle:Long,data:ByteArray,timeout:Int):Int
    external fun close(handle:Long)
}
