#include <jni.h>
#include <libusb.h>
#include <android/log.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,"QuestUvcNative",__VA_ARGS__)
static JavaVM* g_vm=nullptr;

struct Session {
    libusb_context* ctx=nullptr; libusb_device_handle* dev=nullptr; int owned_fd=-1;
    jobject listener=nullptr; jmethodID stats_method=nullptr, frame_method=nullptr;
    std::atomic<bool> running{false}; std::thread event_thread; std::mutex mutex;
    std::vector<libusb_transfer*> transfers; std::vector<unsigned char*> buffers;
    std::vector<unsigned char> frame; int interface_number=-1; uint8_t last_fid=0; bool have_fid=false; bool frame_bad=false;
    uint64_t bytes=0,packets=0,frames=0,corrupt=0,dropped=0,errors=0,total_frame=0; uint32_t min_frame=0,max_frame=0;
    std::chrono::steady_clock::time_point started,last_report,last_preview;
};

static JNIEnv* env_for_thread(bool* detach) { JNIEnv* env=nullptr; *detach=false; if(g_vm->GetEnv((void**)&env,JNI_VERSION_1_6)!=JNI_OK){ if(g_vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return nullptr;*detach=true;}return env; }
static void report(Session* s,const char* error=nullptr) {
    bool detach; JNIEnv* env=env_for_thread(&detach); if(!env)return;
    jlong values[8]={(jlong)s->bytes,(jlong)s->packets,(jlong)s->frames,(jlong)s->corrupt,(jlong)s->dropped,(jlong)s->errors,(jlong)s->min_frame,(jlong)s->max_frame};
    jlongArray array=env->NewLongArray(8); env->SetLongArrayRegion(array,0,8,values);
    auto now=std::chrono::steady_clock::now(); double secs=std::chrono::duration<double>(now-s->started).count(); double fps=secs>0?s->frames/secs:0; double avg=s->frames?s->total_frame/(double)s->frames:0;
    jstring err=error?env->NewStringUTF(error):nullptr; env->CallVoidMethod(s->listener,s->stats_method,array,fps,avg,err);
    if(env->ExceptionCheck()){env->ExceptionDescribe();env->ExceptionClear();} env->DeleteLocalRef(array);if(err)env->DeleteLocalRef(err);if(detach)g_vm->DetachCurrentThread();
}
static void deliver_frame(Session* s) {
    if(s->frame.size()<4 || s->frame[0]!=0xff || s->frame[1]!=0xd8 || s->frame[s->frame.size()-2]!=0xff || s->frame.back()!=0xd9){s->corrupt++;return;}
    size_t n=s->frame.size();s->frames++;s->total_frame+=n;s->min_frame=s->min_frame?std::min<uint32_t>(s->min_frame,n):n;s->max_frame=std::max<uint32_t>(s->max_frame,n);
    auto now=std::chrono::steady_clock::now();if(now-s->last_preview<std::chrono::milliseconds(100))return;s->last_preview=now;
    bool detach;JNIEnv* env=env_for_thread(&detach);if(!env)return;jbyteArray data=env->NewByteArray(n);env->SetByteArrayRegion(data,0,n,(jbyte*)s->frame.data());env->CallVoidMethod(s->listener,s->frame_method,data);if(env->ExceptionCheck())env->ExceptionClear();env->DeleteLocalRef(data);if(detach)g_vm->DetachCurrentThread();
}
static void consume(Session* s,const unsigned char* p,int n) {
    if(n<2){s->errors++;return;} int h=p[0];if(h<2||h>n){s->errors++;return;}uint8_t flags=p[1],fid=flags&1;bool eof=flags&2,err=flags&0x40;
    if(s->have_fid&&fid!=s->last_fid&&!s->frame.empty()){s->dropped++;s->frame.clear();s->frame_bad=false;}s->have_fid=true;s->last_fid=fid;if(err)s->frame_bad=true;
    if(n>h && s->frame.size()+(n-h)<16*1024*1024)s->frame.insert(s->frame.end(),p+h,p+n);else if(n>h)s->frame_bad=true;
    if(eof){if(s->frame_bad)s->corrupt++;else deliver_frame(s);s->frame.clear();s->frame_bad=false;}
}
static void LIBUSB_CALL complete(libusb_transfer* t) {
    auto* s=(Session*)t->user_data;if(!s)return;
    std::lock_guard<std::mutex> lock(s->mutex);
    if(t->status==LIBUSB_TRANSFER_COMPLETED){
        if(t->type==LIBUSB_TRANSFER_TYPE_ISOCHRONOUS){for(int i=0;i<t->num_iso_packets;i++){auto& d=t->iso_packet_desc[i];if(d.status==LIBUSB_TRANSFER_COMPLETED&&d.actual_length){s->bytes+=d.actual_length;s->packets++;consume(s,libusb_get_iso_packet_buffer_simple(t,i),d.actual_length);}else if(d.status!=LIBUSB_TRANSFER_COMPLETED)s->errors++;}}
        else if(t->actual_length>0){s->bytes+=t->actual_length;s->packets++;consume(s,t->buffer,t->actual_length);}
    } else if(t->status!=LIBUSB_TRANSFER_CANCELLED && t->status!=LIBUSB_TRANSFER_TIMED_OUT)s->errors++;
    auto now=std::chrono::steady_clock::now();if(now-s->last_report>std::chrono::milliseconds(500)){s->last_report=now;report(s);}
    if(s->running && t->status!=LIBUSB_TRANSFER_NO_DEVICE){int rc=libusb_submit_transfer(t);if(rc<0){s->errors++;s->running=false;}}
}
static void stop_session(Session* s){
    if(!s)return;s->running=false;for(auto* t:s->transfers)libusb_cancel_transfer(t);if(s->event_thread.joinable())s->event_thread.join();
    std::lock_guard<std::mutex> lock(s->mutex);for(auto* t:s->transfers)libusb_free_transfer(t);for(auto* b:s->buffers)free(b);s->transfers.clear();s->buffers.clear();if(s->interface_number>=0){libusb_set_interface_alt_setting(s->dev,s->interface_number,0);libusb_release_interface(s->dev,s->interface_number);s->interface_number=-1;}report(s);
}
static std::string probe(Session* s,int iface,int format,int frame,uint32_t interval,uint32_t frame_bytes,uint32_t payload){
    if(s->interface_number<0){int claim=libusb_claim_interface(s->dev,iface);if(claim<0)return "CLAIM INTERFACE: "+std::to_string(claim);s->interface_number=iface;}
    else if(s->interface_number!=iface)return "CLAIM INTERFACE: "+std::to_string(LIBUSB_ERROR_BUSY);
    int alt=libusb_set_interface_alt_setting(s->dev,iface,0);if(alt<0)return "SET ALT 0: "+std::to_string(alt);
    unsigned char ctrl[34]{};ctrl[0]=1;ctrl[2]=format;ctrl[3]=frame;ctrl[4]=interval;ctrl[5]=interval>>8;ctrl[6]=interval>>16;ctrl[7]=interval>>24;
    auto put32=[&](int o,uint32_t v){ctrl[o]=v;ctrl[o+1]=v>>8;ctrl[o+2]=v>>16;ctrl[o+3]=v>>24;};put32(18,frame_bytes);put32(22,payload);
    int len=34,rc=libusb_control_transfer(s->dev,0x21,0x01,0x0100,iface,ctrl,len,1000);if(rc<0){len=26;rc=libusb_control_transfer(s->dev,0x21,0x01,0x0100,iface,ctrl,len,1000);}if(rc<0)return "SET_CUR PROBE: "+std::to_string(rc);
    memset(ctrl,0,sizeof(ctrl));rc=libusb_control_transfer(s->dev,0xa1,0x81,0x0100,iface,ctrl,len,1000);if(rc<0)return "GET_CUR PROBE: "+std::to_string(rc);
    rc=libusb_control_transfer(s->dev,0x21,0x01,0x0200,iface,ctrl,len,1000);if(rc<0)return "SET_CUR COMMIT: "+std::to_string(rc);
    uint32_t negotiated=ctrl[22]|(ctrl[23]<<8)|(ctrl[24]<<16)|(ctrl[25]<<24);return "OK · "+std::to_string(len)+" bytes · payload "+std::to_string(negotiated);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm,void*){g_vm=vm;return JNI_VERSION_1_6;}
extern "C" JNIEXPORT jlong JNICALL Java_com_yayo_questuvc_NativeUvc_open(JNIEnv* env,jclass,jint fd,jbyteArray,jobject listener){
    auto* s=new Session();s->owned_fd=dup(fd);if(s->owned_fd<0){delete s;return -1;}libusb_init_option option{};option.option=LIBUSB_OPTION_NO_DEVICE_DISCOVERY;int rc=libusb_init_context(&s->ctx,&option,1);if(rc<0){close(s->owned_fd);delete s;return rc;}rc=libusb_wrap_sys_device(s->ctx,(intptr_t)s->owned_fd,&s->dev);if(rc<0){libusb_exit(s->ctx);close(s->owned_fd);delete s;return rc;}s->listener=env->NewGlobalRef(listener);jclass cls=env->GetObjectClass(listener);s->stats_method=env->GetMethodID(cls,"onStatistics","([JDDLjava/lang/String;)V");s->frame_method=env->GetMethodID(cls,"onFrame","([B)V");return (jlong)(intptr_t)s;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_yayo_questuvc_NativeUvc_probeCommit(JNIEnv* env,jclass,jlong h,jint iface,jint format,jint frame,jlong interval,jint frameBytes,jint payload){auto result=probe((Session*)(intptr_t)h,iface,format,frame,interval,frameBytes,payload);return env->NewStringUTF(result.c_str());}
extern "C" JNIEXPORT jint JNICALL Java_com_yayo_questuvc_NativeUvc_start(JNIEnv*,jclass,jlong h,jint iface,jint alt,jint endpoint,jint type,jint packetSize){
    auto* s=(Session*)(intptr_t)h;if(!s||s->running)return LIBUSB_ERROR_BUSY;int rc=0;if(s->interface_number<0){rc=libusb_claim_interface(s->dev,iface);if(rc<0)return rc;s->interface_number=iface;}else if(s->interface_number!=iface)return LIBUSB_ERROR_BUSY;rc=libusb_set_interface_alt_setting(s->dev,iface,alt);if(rc<0){libusb_release_interface(s->dev,iface);s->interface_number=-1;return rc;}s->bytes=s->packets=s->frames=s->corrupt=s->dropped=s->errors=s->total_frame=0;s->min_frame=s->max_frame=0;s->frame.clear();s->have_fid=false;s->frame_bad=false;s->started=s->last_report=std::chrono::steady_clock::now();s->last_preview=s->started-std::chrono::seconds(1);s->running=true;
    int count=8;for(int i=0;i<count;i++){bool iso=type==1;int packets=iso?32:0;int size=iso?packetSize*packets:std::max(packetSize*16,16384);auto* b=(unsigned char*)malloc(size);auto* t=libusb_alloc_transfer(packets);if(!b||!t){if(t)libusb_free_transfer(t);if(b)free(b);for(auto* old:s->transfers)libusb_free_transfer(old);for(auto* old:s->buffers)free(old);s->transfers.clear();s->buffers.clear();s->running=false;libusb_set_interface_alt_setting(s->dev,iface,0);libusb_release_interface(s->dev,iface);s->interface_number=-1;return LIBUSB_ERROR_NO_MEM;}s->buffers.push_back(b);s->transfers.push_back(t);if(iso){libusb_fill_iso_transfer(t,s->dev,endpoint,b,size,packets,complete,s,1000);libusb_set_iso_packet_lengths(t,packetSize);}else libusb_fill_bulk_transfer(t,s->dev,endpoint,b,size,complete,s,1000);}
    for(size_t i=0;i<s->transfers.size();i++){rc=libusb_submit_transfer(s->transfers[i]);if(rc<0){s->running=false;for(size_t j=0;j<i;j++)libusb_cancel_transfer(s->transfers[j]);for(int k=0;k<20;k++){timeval tv{0,10000};libusb_handle_events_timeout_completed(s->ctx,&tv,nullptr);}for(auto* old:s->transfers)libusb_free_transfer(old);for(auto* old:s->buffers)free(old);s->transfers.clear();s->buffers.clear();libusb_set_interface_alt_setting(s->dev,iface,0);libusb_release_interface(s->dev,iface);s->interface_number=-1;return rc;}}
    s->event_thread=std::thread([s]{while(s->running){timeval tv{0,100000};libusb_handle_events_timeout_completed(s->ctx,&tv,nullptr);}for(int i=0;i<20;i++){timeval tv{0,10000};libusb_handle_events_timeout_completed(s->ctx,&tv,nullptr);}});return 0;
}
extern "C" JNIEXPORT void JNICALL Java_com_yayo_questuvc_NativeUvc_stop(JNIEnv*,jclass,jlong h){stop_session((Session*)(intptr_t)h);}
extern "C" JNIEXPORT void JNICALL Java_com_yayo_questuvc_NativeUvc_close(JNIEnv* env,jclass,jlong h){auto* s=(Session*)(intptr_t)h;if(!s)return;stop_session(s);if(s->dev)libusb_close(s->dev);if(s->ctx)libusb_exit(s->ctx);if(s->owned_fd>=0)close(s->owned_fd);if(s->listener)env->DeleteGlobalRef(s->listener);delete s;}
