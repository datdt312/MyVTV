package com.example.ffmpegvideorange2

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.example.ffmpegvideorange2.databinding.ActivityRangeTimeLineBinding
import com.example.ffmpegvideorange2.helper.IFFmpegCodecFrameHelper
import com.example.ffmpegvideorange2.helper.IMediaCodecFrameHelper
import com.example.ffmpegvideorange2.scrollVelocity.RecyclerVelocityHandler
import com.example.ffmpegvideorange2.scrollVelocity.VelocityTrackListener
import com.example.myplayer.KzgPlayer
import com.example.myplayer.KzgPlayer.PLAY_MODEL_DEFAULT
import com.example.myplayer.KzgPlayer.PlayerListener
import com.example.myplayer.PacketBean
import com.example.myplayer.TimeInfoBean
import com.example.myplayer.opengl.KzgGLSurfaceView
import com.sam.video.timeline.bean.VideoClip
import com.sam.video.timeline.listener.OnFrameClickListener
import com.sam.video.timeline.listener.SelectAreaMagnetOnChangeListener
import com.sam.video.timeline.listener.VideoPlayerOperate
import com.sam.video.timeline.widget.TimeLineBaseValue
import com.sam.video.timeline.widget.VideoFrameRecyclerView
import com.sam.video.timeline.widget.ZoomFrameLayout
import com.sam.video.util.MediaStoreUtil
import com.sam.video.util.VideoUtils
import com.sam.video.util.getScreenWidth
import java.util.*
import kotlin.math.abs


class RangeTimeLineActivity : AppCompatActivity(){

    var inputPath = Environment.getExternalStorageDirectory().toString() + "/video5.mp4"

    private val PLAY_ENABLE_CHANGE_HANDLER = 1001
    private val PLAY_TIME_CHANGE_HANDLER = 1002

    private var kzgPlayer: KzgPlayer? = null
    //记录上次更新播放时间的时间点
    private var lastTime: Long = 0
    private var lastTime2: Long = 0
    //预览条跟随播放的时间
    private var playTimeLine:Long = 0
    private var lastDx = 0

    private val videos = mutableListOf<VideoClip>()
    val timeLineValue = TimeLineBaseValue()
    private var lastScrollTime = 0L
    //private var keyFramesTime:IFrameSearch? = null

    private var handler:Handler? = null
    //预览条是否停止滚动
    private var timeLineScrollIsStop = true;
    //当向前快速滑动的时候，就直接seek到过程中需要的帧，而不是顺序解码
    private var isDoSeekForPriviewFrame = false
    //记录上一次是是否是直接seek
    private var lastIsDoSeek = false
    //是否发生了快速滚动，只要在一次滚动过程中 isDoSeekForPriviewFrame被赋值过为true 就算
    private var hasFastScoll = false


    private lateinit var  binding: ActivityRangeTimeLineBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRangeTimeLineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        inputPath = intent.getStringExtra("filePath").toString()
        binding.avLoading.show()
        initView()
        initAction()


        handler = Handler { msg ->
            when (msg.what) {
                //播放其初始话结束
                PLAY_ENABLE_CHANGE_HANDLER -> if (binding.ivPlayStopVideo != null && binding.ivPlayStopVideo.getVisibility() == View.GONE && msg.obj as Boolean) {
                    binding.ivPlayStopVideo.setVisibility(View.VISIBLE)
                    binding.avLoading.hide()
                }

                //处理正常播放时，预览条的跟随滚动
                PLAY_TIME_CHANGE_HANDLER ->{
                    kzgPlayer?.let {
                        if (it.playModel == KzgPlayer.PLAY_MODEL_DEFAULT) {
                            playTimeLine += msg.obj as Long
                            binding.zoomFrameLayout.scrollByTime(20)

                            //Log.e("kzg","***********************playTimeLine:$playTimeLine  , lastTime2:$lastTime2")
                            val message = Message()
                            message.obj = 20_000L
                            message.what = PLAY_TIME_CHANGE_HANDLER
                            if (lastTime2 - playTimeLine > 10000){
                                //将实际播放时间与预览条滚动进行一个同步
                                // TODO 这种处理感觉预览条任然不是很流畅，需要找到更好的办法
                                handler?.sendMessageDelayed(message,20 - ((lastTime2 - playTimeLine)/1000))
                            }else{
                                handler?.sendMessageDelayed(message,20)
                            }
                        }
                    }
                }
            }
            false
        }
    }


    private fun initView(){
        kzgPlayer = KzgPlayer()
        kzgPlayer!!.setKzgGLSurfaceView(binding.svVideoView)
        binding.ivPlayStopVideo.postDelayed({ begin()},1000)



        val halfScreenWidth = binding.rvFrame.context.getScreenWidth() / 2
        binding.rvFrame.setPadding(halfScreenWidth, 0, halfScreenWidth, 0)
        binding.rvFrame.addOnItemTouchListener(object : OnFrameClickListener(binding.rvFrame) {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                return true
            }

            override fun onLongClick(e: MotionEvent): Boolean {
                return false

            }

            override fun onClick(e: MotionEvent): Boolean {
                //点击的位置
                binding.rvFrame.findVideoByX(e.x)?.let {
                    if (binding.rvFrame.findVideoByX(binding.rvFrame.paddingLeft.toFloat()) == it) {
                        //已选中，切换状态
                        selectVideo = if (selectVideo == it) {
                            null
                        } else {
                            it
                        }
                    } else {
                        //移动用户点击的位置到中间
                        binding.rvFrame.postDelayed(
                            {
                                if (selectVideo != null) {
                                    selectVideo = binding.rvFrame.findVideoByX(e.x)
                                }
                                binding.rvFrame.smoothScrollBy((e.x - binding.rvFrame.paddingLeft).toInt(), 0)
                            },
                            100
                        )
                    }
                } ?: run {
                    selectVideo?.let { selectVideo = null }
                    return false
                }

                return true
            }
        })

        val handler = RecyclerVelocityHandler(this)
        handler.setVelocityTrackerListener(object :VelocityTrackListener{
            override fun onVelocityChanged(velocity: Int) {
                //更新是否停止滚动的状态
                //Log.e("kzg","**********************onVelocityChanged:$velocity  , isSeekBack:${binding.rvFrame.getAvFrameHelper()?.isSeekBack }")
                enablePlayStatus(velocity.toFloat())
                //如果正在播放，就停止播放
                if (KzgPlayer.playModel == KzgPlayer.PLAY_MODEL_DEFAULT){
                    stopPlayVideo()
                }
                if (velocity > 2500){
                    isDoSeekForPriviewFrame = true
                    hasFastScoll = true
                }else if (velocity < 2500){
                    isDoSeekForPriviewFrame = false
                }
                //如果是向后滑动，只有当速度停下来才开始解码
                if (binding.rvFrame.getAvFrameHelper()?.isSeekBack == true && velocity == 0){
                    Log.e("kzg","**************seek 4")
                    for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                        binding.rvFrame.getAvFrameHelperByIndex(indx)?.isScrolling = false
                        binding.rvFrame.getAvFrameHelperByIndex(indx)?.seek()
                    }
                }

                //如果是向后滑动，就停止解码
                if (velocity < 0){
                    if (binding.rvFrame.getAvFrameHelper()?.isScrolling == false){
                        for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.isScrolling = true
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.pause()
                        }
                    }
                }

                if (velocity > 0){
                    //预览条向前滑动

                    if (binding.rvFrame.getAvFrameHelper()?.isSeekBack == true){
                        for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.isSeekBack = false
                        }
                    }
                }else if(velocity < 0){
                    //预览条向后滑动
                    if (binding.rvFrame.getAvFrameHelper()?.isSeekBack == false){
                        for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.isSeekBack = true
                        }
                    }
                }

                //这里处理快速滑动后，突然又反方向滑动，可能存在暂停了解码后无法回复解码的情况
                if (velocity == 0 && binding.rvFrame.getAvFrameHelper()?.isPause == true){
                    Log.e("kzg","**************seek 5")
                    for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                        binding.rvFrame.getAvFrameHelperByIndex(indx)?.isScrolling = false
                        binding.rvFrame.getAvFrameHelperByIndex(indx)?.seek()
                    }
                }

                //快速滑动停止后，需要告诉底层最后停留在了那一帧
                if (velocity == 0 && hasFastScoll){
                    hasFastScoll = false
                    Log.e("kzg","***********************开始解码显示最后一帧:${lastScrollTime}")
                    kzgPlayer?.showFrame(lastScrollTime.toDouble()/1000, KzgPlayer.seek_advance,true,binding.rvFrame.currentVideoDataIndex)
                }


            }

            override fun onScrollFast(velocity:Int) {
                if (binding.rvFrame.getAvFrameHelper()?.isSeekBack == false){
                    //快速向前滚动，暂停解码
                    for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                        binding.rvFrame.getAvFrameHelperByIndex(indx)?.isScrolling = true
                        binding.rvFrame.getAvFrameHelperByIndex(indx)?.pause()
                    }
                }
            }

            override fun onScrollSlow(velocity:Int) {
                clearSelectVideoIfNeed()
                //向前滚动速度慢下来，开始解码
                if (binding.rvFrame.getAvFrameHelper()?.isSeekBack == false){
                    Log.e("kzg","**************seek 6")
                    for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                        binding.rvFrame.getAvFrameHelperByIndex(indx)?.isScrolling = false
                        binding.rvFrame.getAvFrameHelperByIndex(indx)?.seek()
                    }
                }
            }
        })
        binding.rvFrame.addOnScrollListener(handler)

        bindVideoData()

        val duration = VideoUtils.getVideoDuration(this, inputPath)
        videos.add(
            VideoClip(
                UUID.randomUUID().toString(), inputPath,
                duration, 0, duration,videos.size
            )
        )
        updateVideos()

    }

    private fun initAction(){

        //开始或者暂停播放
        binding.ivPlayStopVideo.setOnClickListener {

            if (kzgPlayer == null) {
                return@setOnClickListener
            }
            if (KzgPlayer.playModel == KzgPlayer.PLAY_MODEL_DEFAULT) {
                //停止
                stopPlayVideo()
            } else if (KzgPlayer.playModel == KzgPlayer.PLAY_MODEL_FRAME_PREVIEW) {
                //播放
                if (!kzgPlayer!!.enablePlay  || !timeLineScrollIsStop) {
                    return@setOnClickListener
                }
                binding.rvFrame.getAvFrameHelper()?.removeAvFrame()
                for (i in 0 until videos.size){
                    kzgPlayer!!.setPlayModel(KzgPlayer.PLAY_MODEL_FRAME_PREVIEW,i)
                }
                kzgPlayer!!.setPlayModel(KzgPlayer.PLAY_MODEL_DEFAULT,binding.rvFrame.currentVideoDataIndex)
                kzgPlayer!!.setPlayModelAll(KzgPlayer.PLAY_MODEL_DEFAULT)
                binding.ivPlayStopVideo.setImageResource(R.drawable.stop_ico)

                if (binding.zoomFrameLayout != null && kzgPlayer!!.playModel == KzgPlayer.PLAY_MODEL_DEFAULT) {
                    playTimeLine = lastTime2
                    val message = Message()
                    message.obj = 10_000L
                    message.what = PLAY_TIME_CHANGE_HANDLER
                    handler?.sendMessage(message)
                }
            }
        }


        //预览图滑动监听
        binding.zoomFrameLayout.timeChangeListener = object :VideoPlayerOperate {
            override fun updateVideoInfo() {

            }

            override fun startTrackingTouch() {

            }

            override fun stopTrackingTouch(ms: Long) {

            }

            override fun updateTimeByScroll(time: Long) {
                //逐帧预览时，才处理滚动
                if ( KzgPlayer.PLAY_MODEL_FRAME_PREVIEW == kzgPlayer?.playModel){
                    //计算正确的时间，即：需要减去之前的视频段的时间，再加上偏移的开始时间
                    val newTime = time - binding.rvFrame.preVideoTime + videos[binding.rvFrame.currentVideoDataIndex].startAtMs

                    binding.rvFrame.getAvFrameHelper()?.iframeSearch?.getCurrentGopFromTimeUs(newTime*1000)

                    if (newTime > lastScrollTime){
                        //向前滚动
                        if (!isDoSeekForPriviewFrame) {
                            Log.e("kzg","*********************currentIFrame: , time:$newTime  , index:${binding.rvFrame.currentVideoDataIndex}")
                            kzgPlayer?.showFrame(newTime.toDouble()/1000, KzgPlayer.seek_advance,false,binding.rvFrame.currentVideoDataIndex)
                        }else{
                            val currentIFrame= binding.rvFrame.getAvFrameHelper()?.iframeSearch?.currentIFrameTimeUs?:0
                            Log.e("kzg","*********************currentIFrame:$currentIFrame  , time:$newTime , index:${binding.rvFrame.currentVideoDataIndex}")
                            kzgPlayer?.showFrame(currentIFrame.toDouble()/1000_000, KzgPlayer.seek_back,true,binding.rvFrame.currentVideoDataIndex)
                        }


                    }else if(newTime < lastScrollTime) {
                        //向后滚动
                        Log.e("kzg","*********************currentIFrame: , time:$newTime  , index:${binding.rvFrame.currentVideoDataIndex}")
                        if (newTime < 0 && binding.rvFrame.currentVideoDataIndex > 0){
                            kzgPlayer?.showFrame((binding.rvFrame!!.videoData!![binding.rvFrame.currentVideoDataIndex - 1].durationMs + newTime).toDouble()/1000, KzgPlayer.seek_back,false,binding.rvFrame.currentVideoDataIndex - 1)
                        }else{
                            kzgPlayer?.showFrame(newTime.toDouble()/1000, KzgPlayer.seek_back,false,binding.rvFrame.currentVideoDataIndex)
                        }

                    }
                    lastScrollTime = newTime
                    lastTime2 = time * 1000
                    //更新播放时间
                    updatePlayTime(time * 1000)
                }

            }

        }

        binding.zoomFrameLayout.onScrollVelocityChangeListener = object : ZoomFrameLayout.OnScrollVelocityChangeListener{
            //惯性滑动才会触发
            override fun onVelocityChange(v: Float) {
                //更新是否停止滚动的状态
                enablePlayStatus(v)
                //如果正在播放，就停止播放
                if (KzgPlayer.playModel == KzgPlayer.PLAY_MODEL_DEFAULT){
                    stopPlayVideo()
                }
                Log.e("kzg","**********************onVelocityChange:$v  ,timeLineScrollIsStop:${timeLineScrollIsStop}")
                if (v > 0F){
                    //预览条向前滑动
                    if (v >= 200){
                        isDoSeekForPriviewFrame = true
                        hasFastScoll = true
                    }else{
                        isDoSeekForPriviewFrame = false
                    }

                    if (binding.rvFrame.getAvFrameHelper()?.isSeekBack == true){
                        for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.isSeekBack = false
                        }
                    }


                    //如果向前滑动，并且速度大于60 并且isScrolling=false,就暂停解码
                    if (v > 50F && binding.rvFrame.getAvFrameHelper()?.isScrolling == false){
                        for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.isScrolling = true
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.pause()
                        }
                    }

                    //如果是向前滑动，并且速度小于60 并且isScrolling=true 就开始解码
                    if (v < 50F && binding.rvFrame.getAvFrameHelper()?.isScrolling == true){
                        Log.e("kzg","**************seek 1")
                        for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.isScrolling = false
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.seek()
                        }
                    }

                }else if (v < 0F){
                    //预览条向后滑动
                    if (binding.rvFrame.getAvFrameHelper()?.isSeekBack == false){
                        for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.isSeekBack = true
                        }
                    }
                    //如果是向后滑动，并且isScrolling=false,速度大于50 就暂停解码
                    if (binding.rvFrame.getAvFrameHelper()?.isScrolling == false && v < -50){
                        for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.isScrolling = true
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.pause()
                        }
                    }
                }
                //如果是向后滑动，并且速度为0，并且isScrolling=true,就开始解码
                if (v == 0F && binding.rvFrame.getAvFrameHelper()?.isScrolling == true){
                    Log.e("kzg","**************seek 2")
                    for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                        binding.rvFrame.getAvFrameHelperByIndex(indx)?.isScrolling = false
                        binding.rvFrame.getAvFrameHelperByIndex(indx)?.seek()
                    }
                }

                if (v == 0F && hasFastScoll) {
                    hasFastScoll = false
                    Log.e("kzg","************************lastScrollTime:$lastScrollTime")
                    kzgPlayer?.showFrame(lastScrollTime.toDouble()/1000, KzgPlayer.seek_advance,true,binding.rvFrame.currentVideoDataIndex)
                    //kzgPlayer?.showFrame(lastScrollTime.toDouble()/1000, KzgPlayer.seek_back,true)
                }
            }

            //binding.zoomFrameLayout 滚动
            override fun onScrollZoomFl(x:Int) {
                stopPlayVideo()
                if (x <= 0){
                    //预览条向后滑动
                    if (binding.rvFrame.getAvFrameHelper()?.isSeekBack == false){
                        for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.isSeekBack = true
                        }
                    }

                    if (binding.rvFrame.getAvFrameHelper()?.isScrolling == false && x < 0){
                        for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.isScrolling = true
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.pause()
                        }
                    }
                    //Log.e("kzg","**********************onScrollBack:$x  ,${binding.rvFrame.getAvFrameHelper()?.isScrolling}")
                    if (x > -3 && binding.rvFrame.getAvFrameHelper()?.isScrolling == true){
                        Log.e("kzg","**************seek 3")
                        for (indx in 0 until  (binding.rvFrame.videoData?.size?:0)){
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.isScrolling = false
                            binding.rvFrame.getAvFrameHelperByIndex(indx)?.seek()
                        }
                    }
                }

            }

        }

        //添加视频
        binding.ivAddVideo.setOnClickListener {
            startGetVideoIntent()
        }

        binding.rvFrame.onChangeListener = object :VideoFrameRecyclerView.OnVideoChangeListener{
            override fun onChangeVideo(lastVideoInde: Int, currentVideoIndex: Int) {
                //当发生视频切换的时候
                if (kzgPlayer?.playModel == KzgPlayer.PLAY_MODEL_DEFAULT){
                    kzgPlayer?.setPlayModel(KzgPlayer.PLAY_MODEL_FRAME_PREVIEW,lastVideoInde)
                    kzgPlayer?.setPlayModel(KzgPlayer.PLAY_MODEL_DEFAULT,currentVideoIndex)
                }

                Log.e("kzg","******************onChangeVideo last:$lastVideoInde, current:$currentVideoIndex")
            }

        }

        binding.tvChakan.setOnClickListener {
            (binding.rvFrame.getAvFrameHelperByIndex(1) as IFFmpegCodecFrameHelper).showTargetViewMap()

        }
    }


    fun begin() {
        kzgPlayer!!.setSource(inputPath)
        kzgPlayer!!.setPlayModelAll(KzgPlayer.PLAY_MODEL_FRAME_PREVIEW)
        kzgPlayer!!.setPlayModel(KzgPlayer.PLAY_MODEL_FRAME_PREVIEW,0)
        kzgPlayer!!.parpared()
        kzgPlayer!!.setPlayerListener(object : PlayerListener {
            override fun onError(code: Int, msg: String) {
                Log.e("kzg", "************************error:$msg")
                runOnUiThread { binding.avLoading.hide() }
            }

            override fun onPrepare(index: Int) {
                Log.e("kzg", "*********************onPrepare success")
                if (index == 0){
                    kzgPlayer!!.start(index)
                }else{
                    kzgPlayer!!.startForAddVideo(index)
                }
            }

            override fun onLoadChange(isLoad: Boolean) {
                if (isLoad) {
                    Log.e("kzg", "开始加载")
                } else {
                    Log.e("kzg", "加载结束")
                }
            }

            override fun onProgress(currentTime: Long, totalTime: Long) {
                //正常播放进度
                //
                // -++Log.e("kzg", "******************onProgress:$currentTime ,preVideoTime:${binding.rvFrame.preVideoTime}")
                if (kzgPlayer?.playModel == PLAY_MODEL_DEFAULT){
                    lastTime2 = currentTime + binding.rvFrame.preVideoTime*1000
                    //更新播放时间
                    updatePlayTime(currentTime + binding.rvFrame.preVideoTime*1000)
                }

            }

            override fun onTimeInfo(timeInfoBean: TimeInfoBean) {
                Log.e("kzg", "*********************timeInfoBean:$timeInfoBean")
            }

            override fun onEnablePlayChange(enable: Boolean) {
                //播放器
                if (handler != null) {
                    val message = Message()
                    message.what = PLAY_ENABLE_CHANGE_HANDLER
                    message.obj = enable
                    handler?.sendMessage(message)
                }
            }

            override fun onPlayStop() {
                kzgPlayer!!.setPlayModelAll(KzgPlayer.PLAY_MODEL_FRAME_PREVIEW)
                for (i in 0 until videos.size){
                    kzgPlayer!!.setPlayModel(KzgPlayer.PLAY_MODEL_FRAME_PREVIEW,i)
                }
                binding.ivPlayStopVideo.setImageResource(R.drawable.play_ico)
            }

            override fun onComplete() {
                Log.e("kzg", "*********************onComplete:")
            }

            override fun onDB(db: Int) {
                //Log.e("kzg","**********************onDB:"+db);
            }

            override fun onGetVideoInfo(fps: Int, duration: Long, widht: Int, height: Int) {
                runOnUiThread { changeSurfaceViewSize(widht, height) }
            }
        })


        val avFrameHelper = IFFmpegCodecFrameHelper(inputPath,null)
        avFrameHelper.setKzgPlayer(kzgPlayer!!)
        binding.rvFrame.setAvFrameHelper(avFrameHelper)

        kzgPlayer!!.initGetFrame(inputPath,binding.rvFrame.getAvFrameHelper()?.videoIndex?:0)
        kzgPlayer!!.setGetFrameListener(object :KzgPlayer.GetFrameListener{
            override fun onInited(
                codecName: String?,
                width: Int,
                height: Int,
                csd_0: ByteArray?,
                csd_1: ByteArray?,
                index:Int
            ) {
                if (binding.rvFrame.getAvFrameHelper() is IMediaCodecFrameHelper){
                    (binding.rvFrame.getAvFrameHelper() as IMediaCodecFrameHelper).initMediaCodec(codecName,width, height, csd_0, csd_1,binding.svVideoTest.holder.surface)
                }else if(binding.rvFrame.getAvFrameHelper() is IFFmpegCodecFrameHelper){
                    Log.e("kzg","**************onInited:$width,$height,$codecName")
                    kzgPlayer!!.startGetFrame(index)
                    kzgPlayer?.getFrameListener?.onStarGetFrame(index)
                }
            }

            override fun onStarGetFrame(index:Int) {
                runOnUiThread {
                    if (index >= 1){
                        return@runOnUiThread
                    }
                    binding.rvFrame.adapter?.notifyDataSetChanged()
                }
            }

            override fun getFramePacket(dataSize: Int, pts: Double, data: ByteArray?,index:Int) {
                val packetBean = PacketBean()
                packetBean.data = data
                packetBean.pts = pts
                packetBean.dataSize = dataSize
                //Log.e("kzg","**************getFramePacket 入队一帧")
                if (binding.rvFrame.getAvFrameHelperByIndex(index) is IMediaCodecFrameHelper){
                    (binding.rvFrame.getAvFrameHelperByIndex(index) as IMediaCodecFrameHelper).packetQueue.enQueue(packetBean)
                    if ((binding.rvFrame.getAvFrameHelperByIndex(index) as IMediaCodecFrameHelper).packetQueue.queueSize >= 30){
                        binding.rvFrame.getAvFrameHelperByIndex(index)?.pause()
                    }
                }

            }

            override fun onGetFrameYUV(width: Int, height: Int, y: ByteArray?, u: ByteArray?, v: ByteArray?,
                practicalWidth: Int, timeUs:Double,index:Int) {
                runOnUiThread {
                    if (binding.rvFrame.getAvFrameHelper() is IFFmpegCodecFrameHelper){
                        val bean = YUVDataBean()
                        bean.timeUs = timeUs.toLong()
                        bean.width = width
                        bean.practicalWidth = practicalWidth
                        bean.height = height
                        bean.y = y
                        bean.u = u
                        bean.v = v

                        Log.e("kzg","********************yuvQueue:${ (binding.rvFrame.getAvFrameHelperByIndex(index) as IFFmpegCodecFrameHelper).yuvQueue.queueSize}")
                        (binding.rvFrame.getAvFrameHelperByIndex(index) as IFFmpegCodecFrameHelper).yuvQueue.enQueue(bean)
                        if ((binding.rvFrame.getAvFrameHelperByIndex(index) as IFFmpegCodecFrameHelper).yuvQueue.queueSize > 2){
                            binding.rvFrame.getAvFrameHelperByIndex(index)?.pause()
                        }
                    }
                }
            }

            override fun onGetFrameYUV2(width: Int,height: Int,yuv: ByteArray?,practicalWidth: Int,timeUs: Double) {}

        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val data = data ?: return
        if (resultCode == RESULT_OK) {
            if (requestCode == TimeLineActivity.Constants.REQUEST_VIDEO) {
                val uri = data.data
                val path = MediaStoreUtil.audioUriToRealPath(this, uri) ?: return
                val avFrameHelper = IFFmpegCodecFrameHelper(path,null)
                avFrameHelper.setKzgPlayer(kzgPlayer!!)
                binding.rvFrame.setAvFrameHelper(avFrameHelper)
                val duration = VideoUtils.getVideoDuration(this, path)
                videos.add(
                    VideoClip(
                        UUID.randomUUID().toString(), path,
                        duration, 0, duration,videos.size
                    )
                )
                updateVideos()
                kzgPlayer!!.addVideo(path,videos.size - 1)
            }
        }
    }


    //更新播放时间UI
    private fun updatePlayTime(currentTime:Long) {
        //播放时间大于等于100毫米才更新一次播放时间
        if (abs(currentTime - lastTime) > 100 * 1000) {
            lastTime = currentTime
            runOnUiThread {
                binding.tvVideoRangePlayTime.text = Utils.MilliToMinuteTime(
                    currentTime / 1000
                )
            }
        }
    }

    private fun stopPlayVideo(){
        for (i in 0 until videos.size){
            kzgPlayer!!.setPlayModel(KzgPlayer.PLAY_MODEL_FRAME_PREVIEW,i)
        }
        kzgPlayer!!.setPlayModelAll(KzgPlayer.PLAY_MODEL_FRAME_PREVIEW)
        binding.ivPlayStopVideo.setImageResource(R.drawable.play_ico)
    }


    private fun enablePlayStatus(v:Float){
        if (v != 0F){
            timeLineScrollIsStop = false
        }else{
            binding.zoomFrameLayout.postDelayed({ timeLineScrollIsStop = true },200)
        }
    }

    private fun changeSurfaceViewSize(widht: Int, height: Int) {
        var widht = widht
        var height = height
        val surfaceWidth: Int = binding.svVideoView.getWidth()
        val surfaceHeight: Int = binding.svVideoView.getHeight()

        //根据视频尺寸去计算->视频可以在sufaceView中放大的最大倍数。
        val max: Float
        max = if (resources.configuration.orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            //竖屏模式下按视频宽度计算放大倍数值
            Math.max(
                widht.toFloat() / surfaceWidth.toFloat(),
                height.toFloat() / surfaceHeight.toFloat()
            )
        } else {
            //横屏模式下按视频高度计算放大倍数值
            Math.max(
                widht.toFloat() / surfaceHeight.toFloat(),
                height.toFloat() / surfaceWidth.toFloat()
            )
        }

        //视频宽高分别/最大倍数值 计算出放大后的视频尺寸
        widht = Math.ceil((widht.toFloat() / max).toDouble()).toInt()
        height = Math.ceil((height.toFloat() / max).toDouble()).toInt()

        //无法直接设置视频尺寸，将计算出的视频尺寸设置到surfaceView 让视频自动填充。
        val layoutParams: ViewGroup.LayoutParams = binding.svVideoView.getLayoutParams()
        layoutParams.width = widht
        layoutParams.height = height
        binding.svVideoView.layoutParams = layoutParams
    }


    private val videoSelectAreaChangeListener by lazy {
        object : SelectAreaMagnetOnChangeListener(binding.selectAreaView.context) {
            override val timeJumpOffset: Long
                get() = binding.selectAreaView.eventHandle.timeJumpOffset

            override val timeLineValue = (this@RangeTimeLineActivity).timeLineValue

            var downStartAtMs: Long = 0L
            var downEndAtMs: Long = 0L
            var downSpeed: Float = 1f
            override fun onTouchDown() {
                isOperateAreaSelect = true
                val selectVideo = selectVideo ?: return

                //更新边缘，此处边缘不限
                startTimeEdge = 0
                endTimeEdge = Long.MAX_VALUE

                downStartAtMs = selectVideo.startAtMs
                downEndAtMs = selectVideo.endAtMs
            }

            override fun onTouchUp() {
                isOperateAreaSelect = false
            }

            override fun onChange(
                startOffset: Long,
                endOffset: Long,
                fromUser: Boolean
            ): Boolean {
                if (filterOnChange(startOffset, endOffset)) {
                    return true
                }
                val selectVideo = selectVideo ?: return false
                if (startOffset != 0L) {
                    //    - 起始位置移动时，相对时间轴的开始位置其实是不变的，变的是当前选择视频的开始位置+长度 （此时因为总的时间轴变长，所以区域变化了）
                    val oldStartTime = selectVideo.startAtMs
                    selectVideo.startAtMs += (downSpeed * startOffset).toLong()
                    //起始位置 + 吸附产生的时间差
                    selectVideo.startAtMs += checkTimeJump(
                        binding.selectAreaView.startTime,
                        startOffset < 0
                    ) - binding.selectAreaView.startTime

                    if (selectVideo.startAtMs < 0) {
                        selectVideo.startAtMs = 0
                    }
                    if (selectVideo.startAtMs > selectVideo.endAtMs - timeLineValue.minClipTime) {
                        selectVideo.startAtMs = selectVideo.endAtMs - timeLineValue.minClipTime
                    }


                    binding.selectAreaView.endTime =
                        binding.selectAreaView.startTime + selectVideo.durationMs //这样是经过换算的
                    val realOffsetTime = selectVideo.startAtMs - oldStartTime
                    if (fromUser) { //光标位置反向移动，保持时间轴和手的相对位置
                        timeLineValue.time -= (realOffsetTime / downSpeed).toLong()
                        if (timeLineValue.time < 0) {
                            timeLineValue.time = 0
                        }
                    }
                    Log.e("kzg","*************************startAtMs:${selectVideo.startAtMs}")
                    updateVideoClip()
                    return realOffsetTime != 0L
                } else if (endOffset != 0L) {
                    //   - 结束位置移动时，范围的起始位置也不变，结束位置会变。
                    val oldEndMs = selectVideo.endAtMs
                    selectVideo.endAtMs += (downSpeed * endOffset).toLong()
                    binding.selectAreaView.endTime = binding.selectAreaView.startTime + selectVideo.durationMs

                    selectVideo.endAtMs += checkTimeJump(
                        binding.selectAreaView.endTime,
                        endOffset < 0
                    ) - binding.selectAreaView.endTime
                    if (selectVideo.endAtMs < selectVideo.startAtMs + timeLineValue.minClipTime) {
                        selectVideo.endAtMs = selectVideo.startAtMs + timeLineValue.minClipTime
                    }
                    if (selectVideo.endAtMs > selectVideo.originalDurationMs) {
                        selectVideo.endAtMs = selectVideo.originalDurationMs
                    }
                    binding.selectAreaView.endTime = binding.selectAreaView.startTime + selectVideo.durationMs
                    val realOffsetTime = selectVideo.endAtMs - oldEndMs
                    if (!fromUser) {
                        //结束位置，如果是动画，光标需要跟着动画
                        timeLineValue.time += (realOffsetTime / downSpeed).toLong()
                        if (timeLineValue.time < 0) {
                            timeLineValue.time = 0
                        }
                    }
                    updateVideoClip()
                    return realOffsetTime != 0L
                }
                return false
            }
        }
    }


    /** 选段 */
    var selectVideo: VideoClip? = null
        set(value) {
            field = value
            if (value == null) {
                //取消选中
                binding.rvFrame.hasBorder = true
                binding.selectAreaView.visibility = View.GONE
            } else {
                clearTagSelect()
                //选中视频
                binding.selectAreaView.startTime = 0
                binding.selectAreaView.onChangeListener = videoSelectAreaChangeListener
                for ((index, item) in videos.withIndex()) {
                    if (item === value) {
                        binding.selectAreaView.offsetStart = if (index > 0) {
                            binding.rvFrame.halfDurationSpace
                        } else {
                            0
                        }
                        binding.selectAreaView.offsetEnd = if (index < videos.size - 1) {
                            binding.rvFrame.halfDurationSpace
                        } else {
                            0
                        }
                        break
                    }
                    binding.selectAreaView.startTime += item.durationMs
                }
                binding.selectAreaView.endTime = binding.selectAreaView.startTime + value.durationMs
                binding.rvFrame.hasBorder = false
                binding.selectAreaView.visibility = View.VISIBLE
            }
        }

    private fun bindVideoData() {
        binding.zoomFrameLayout.scaleEnable = true
        binding.rvFrame.videoData = videos

        binding.zoomFrameLayout.timeLineValue = timeLineValue
        binding.zoomFrameLayout.dispatchTimeLineValue()
        binding.zoomFrameLayout.dispatchScaleChange()
    }

    /**
     * 是否正在操作区域选择
     */
    private var isOperateAreaSelect = false

    private fun clearSelectVideoIfNeed() {
        if (selectVideo != null && !binding.selectAreaView.timeInArea()
            && !isOperateAreaSelect //未操作区域选择时
        ) {
            selectVideo = null
        }
    }

    /**
     * 清除选中模式
     */
    private fun clearTagSelect() {
        binding.selectAreaView.visibility = View.GONE
        binding.rvFrame.hasBorder = true
        binding.tagView.activeItem = null
    }

    /**
     * 更新视频的截取信息
     * update and dispatch
     * */
    private fun updateVideoClip() {
        updateTimeLineValue(true)
        binding.rvFrame.rebindFrameInfo()
        binding.rulerView.invalidate()
        binding.selectAreaView.invalidate()
    }

    /**
     * 更新全局的时间轴
     * @param fromUser 用户操作引起的，此时不更改缩放尺度
     */
    private fun updateTimeLineValue(fromUser: Boolean = false) {
        /**
        1、UI定一个默认初始长度（约一屏或一屏半），用户导入视频初始都伸缩为初始长度；初始精度根据初始长度和视频时长计算出来；
        2、若用户导入视频拉伸到最长时，总长度还短于初始长度，则原始视频最长能拉到多长就展示多长；
        3、最大精度：即拉伸到极限时，一帧时长暂定0.25秒；
         */
        timeLineValue.apply {
            val isFirst = duration == 0L
            duration = totalDurationMs
            if (time > duration) {
                time = duration
            }

//            if (fromUser || duration == 0L ) {
//                return
//            }
            if (isFirst) {//首次
                resetStandPxInSecond()
            } else {
                fitScaleForScreen()
            }
            binding.zoomFrameLayout.dispatchTimeLineValue()
            binding.zoomFrameLayout.dispatchScaleChange()
        }
    }

    private val totalDurationMs: Long //当前正在播放视频的总时长
        get() {
            var result = 0L
            for (video in videos) {
                result += video.durationMs
            }
            return result
        }

    private fun updateVideos() {
        binding.rvFrame.rebindFrameInfo()
        updateTimeLineValue(false)
    }


    override fun onDestroy() {
        super.onDestroy()
        binding.rvFrame.release()
        handler?.removeCallbacksAndMessages(null)
        if (kzgPlayer != null) {
            kzgPlayer!!.stop()
            kzgPlayer!!.release()
            kzgPlayer!!.setPlayerListener(null)
            kzgPlayer!!.getFrameListener= null
            kzgPlayer = null
        }

        if (binding.svVideoView != null) {
            binding.svVideoView.removeCallbacks(null)

        }
    }


    fun isMainThread(): Boolean {
        return Looper.getMainLooper() == Looper.myLooper()
    }

    //打开系统选择视频界面
    private fun startGetVideoIntent() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.setDataAndType(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            TimeLineActivity.Constants.VIDEO_TYPE
        )
        val chooserIntent = Intent.createChooser(intent, null)
        startActivityForResult(chooserIntent, TimeLineActivity.Constants.REQUEST_VIDEO)
    }
}