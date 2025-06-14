package com.example.ffmpegvideorange2

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.ffmpegvideorange2.databinding.ActivityTimeLineBinding
import com.sam.video.timeline.bean.TagLineViewData
import com.sam.video.timeline.bean.VideoClip
import com.sam.video.timeline.helper.IFrameSearch
import com.sam.video.timeline.listener.Click
import com.sam.video.timeline.listener.OnFrameClickListener
import com.sam.video.timeline.listener.SelectAreaMagnetOnChangeListener
import com.sam.video.timeline.listener.TagSelectAreaMagnetOnChangeListener
import com.sam.video.timeline.widget.TagLineView
import com.sam.video.timeline.widget.TagPopWindow
import com.sam.video.timeline.widget.TimeLineBaseValue
import com.sam.video.util.MediaStoreUtil
import com.sam.video.util.ScreenUtil
import com.sam.video.util.VideoUtils
import com.sam.video.util.getScreenWidth
import java.util.*

class TimeLineActivity : AppCompatActivity(), View.OnClickListener  {
    private val videos = mutableListOf<VideoClip>()
    val timeLineValue = TimeLineBaseValue()
    private var exitTime: Long = 0
    
    private lateinit var binding: ActivityTimeLineBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimeLineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvAddVideo.setOnClickListener(this)
        binding.tvAddTag.setOnClickListener(this)
        binding.ivRemove.setOnClickListener(this)
        binding.zoomFrameLayout.setOnClickListener(this)
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

        binding.rvFrame.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    Log.e("kzg","**************SCROLL_STATE_IDLE")
                    binding.rvFrame.getAvFrameHelper()?.seek()
                    clearSelectVideoIfNeed()
                }else if (newState == RecyclerView.SCROLL_STATE_DRAGGING){
                    binding.rvFrame.getAvFrameHelper()?.pause()
                }else if (newState == RecyclerView.SCROLL_STATE_SETTLING){
                    binding.rvFrame.getAvFrameHelper()?.pause()
                }

            }

        })

        binding.tagView.onItemClickListener = object : TagLineView.OnItemClickListener {
            override fun onItemClick(item: TagLineViewData, x: Float) {
                selectTag(item)
            }

            override fun onItemGroupClick(groupData: List<TagLineViewData>, x: Float) {
                val activeItem = binding.tagView.activeItem
                val isActiveGroup = activeItem != null && groupData.indexOfFirst { it === activeItem } >= 0
                if (isActiveGroup) {
                    showTagPopWindow(groupData, x)
                } else {
                    selectTag(groupData[0])
                }

            }


        }

        bindVideoData()
//        requestPermission()

    }


    private var tagPopWindow: TagPopWindow? = null
    private val windowHeight = ScreenUtil.getInstance().realSizeHeight
    private fun showTagPopWindow(tagList: List<TagLineViewData>, x: Float) {
        val popWindow = tagPopWindow ?: TagPopWindow(binding.zoomFrameLayout.context).also {
            tagPopWindow = it
            it.onItemClickListener = Click.OnItemViewClickListener { _, t, _ ->
                selectTag(t, true)
                tagPopWindow?.dismiss()
            }
        }
        popWindow.updateData(tagList, binding.tagView.activeItem)
        val location = IntArray(2)
        binding.tagView.getLocationInWindow(location)
        popWindow.showAtTriangleX(binding.tagView, x.toInt(), windowHeight - location[1]) //这里的位置计算考虑了最高选中的情况
    }

    private val videoSelectAreaChangeListener by lazy {
        object : SelectAreaMagnetOnChangeListener(binding.selectAreaView.context) {
            override val timeJumpOffset: Long
                get() = binding.selectAreaView.eventHandle.timeJumpOffset

            override val timeLineValue = (this@TimeLineActivity).timeLineValue

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

    private val tagSelectAreaChangeListener: TagSelectAreaMagnetOnChangeListener by lazy {
        object : TagSelectAreaMagnetOnChangeListener(binding.tagView, binding.tagView.context) {
            override val timeJumpOffset: Long
                get() = binding.selectAreaView.eventHandle.timeJumpOffset

            override fun onTouchDown() {
                endTimeEdge = timeLineValue?.duration ?: 0L
            }


            override val timeLineValue: TimeLineBaseValue?
                get() = binding.zoomFrameLayout.timeLineValue

            override fun afterSelectAreaChange(realOffset: Long, fromUser: Boolean) {
                handleAfterSelectAreaChange(realOffset, fromUser)
            }
        }
    }

    private fun handleAfterSelectAreaChange(realOffset: Long, fromUser: Boolean) {
        if (realOffset == 0L) {
            return
        }

        binding.tagView.dataChange()
        binding.tagView.activeItem?.let { TagLineViewData ->
            binding.selectAreaView.startTime = TagLineViewData.startTime
            binding.selectAreaView.endTime = TagLineViewData.endTime
        }

        if (fromUser) {
            binding.selectAreaView.invalidate()
        } else {
            timeLineValue.time += realOffset
            binding.zoomFrameLayout.dispatchUpdateTime()
        }

    }

    /**
     * 选中标签
     * @param moveTop 移到顶部
     */
    private fun selectTag(item: TagLineViewData, moveTop: Boolean = false) {
        selectVideo = null
        binding.rvFrame.hasBorder = false
        binding.tagView.activeItem = item
        binding.selectAreaView.startTime = item.startTime
        binding.selectAreaView.endTime = item.endTime
        binding.selectAreaView.visibility = View.VISIBLE
        binding.selectAreaView.onChangeListener = tagSelectAreaChangeListener
        binding.selectAreaView.invalidate()
        if (moveTop) {
            binding.tagView.bringTagToTop(item)
        } else {
            binding.tagView.invalidate()
        }
        binding.ivRemove.visibility = View.VISIBLE
        tagSelectAreaChangeListener.updateTimeSetData(item)

    }
    /**
     * 清除选中模式
     */
    private fun clearTagSelect() {
        binding.selectAreaView.visibility = View.GONE
        binding.rvFrame.hasBorder = true
        binding.tagView.activeItem = null
    }


    private fun bindVideoData() {
        binding.zoomFrameLayout.scaleEnable = true
        binding.rvFrame.videoData = videos

        binding.zoomFrameLayout.timeLineValue = timeLineValue
        binding.zoomFrameLayout.dispatchTimeLineValue()
        binding.zoomFrameLayout.dispatchScaleChange()
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

    override fun onClick(v: View?) {
        when (v) {
            binding.tvAddVideo -> startGetVideoIntent()
            binding.ivRemove -> removeLastVideo()
            binding.tvAddTag -> addTagClick()
            binding.zoomFrameLayout -> clearTagSelect()
        }
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

    //打开系统选择视频界面
    private fun startGetVideoIntent() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.setDataAndType(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            Constants.VIDEO_TYPE
        )
        val chooserIntent = Intent.createChooser(intent, null)
        startActivityForResult(chooserIntent, Constants.REQUEST_VIDEO)
    }

    private fun removeLastVideo() {
        if (videos.size > 0) {
            videos.removeAt(videos.size - 1)
        }
        updateVideos()
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val data = data ?: return
        if (resultCode == RESULT_OK) {
            if (requestCode == Constants.REQUEST_VIDEO) {
                val uri = data.data
                val path = MediaStoreUtil.audioUriToRealPath(this, uri) ?: return
                //binding.rvFrame?.setAvFrameHelper(MediaCodecAvFrameHelper(path,null))
                //binding.rvFrame?.setVideoDecoder(VideoDecoder2(path))
                val duration = VideoUtils.getVideoDuration(this, path)
                videos.add(
                    VideoClip(
                        UUID.randomUUID().toString(), path,
                        duration, 0, duration
                    )
                )
                updateVideos()

                val keyFramesTime = IFrameSearch(path)
            }
        }
    }

    private fun updateVideos() {
        binding.rvFrame.rebindFrameInfo()
        updateTimeLineValue(false)
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

    var mListener: PermissionListener? = null
    private fun requestRuntimePermission(
        permissions: Array<String>,
        listener: PermissionListener
    ) { // 获取栈顶Activity
        val topActivity: Activity = this
        mListener = listener
        // 需要请求的权限列表
        val requestPermisssionList: MutableList<String> =
            ArrayList()
        // 检查权限 是否已被授权
        for (permission in permissions) {
            if (ActivityCompat.checkSelfPermission(
                    topActivity,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) // 未授权时添加该权限
                requestPermisssionList.add(permission)
        }
        if (requestPermisssionList.isEmpty()) // 所有权限已经被授权过 回调Listener onGranted方法 已授权
            listener.onGranted() else  // 进行请求权限操作
            ActivityCompat.requestPermissions(
                topActivity,
                requestPermisssionList.toTypedArray(),
                Constants.REQUEST_CODE
            )
    }


    // 请求权限的回调
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            Constants.REQUEST_CODE -> {
                val deniedPermissionList: MutableList<String> =
                    ArrayList()
                // 检查返回授权结果不为空
                if (grantResults.size > 0) { // 判断授权结果
                    var i = 0
                    while (i < grantResults.size) {
                        val result = grantResults[i]
                        if (result != PackageManager.PERMISSION_GRANTED) // 保存被用户拒绝的权限
                            deniedPermissionList.add(permissions[i])
                        i++
                    }
                    if (deniedPermissionList.isEmpty()) { // 都被授权 回调Listener onGranted方法 已授权
                        mListener?.onGranted()
                    } else {  // 有权限被拒绝 回调Listner onDeynied方法
                        mListener?.onDenied(deniedPermissionList)
                    }
                }
            }
        }
    }

    fun addTagClick() {
        addTag("Android")
        addTag("面试官")
        addTag("山言两语")

        binding.tagView.addTextTag(
            "啦啦啦",
            500L,
            3000L,
            binding.tagView.getRandomColorForText()
        )
    }

    /**
     * 添加 视频贴纸标签
     */
    @MainThread
    private fun addTag(text: String) {
        binding.tagView.addTextTag(
            text,
            0L,
            1000L,
            binding.tagView.getRandomColorForText()
        )
    }


    interface PermissionListener {
        /**
         * 授权成功
         */
        fun onGranted()

        /**
         * 授权失败
         *
         * @param deniedPermission
         */
        fun onDenied(deniedPermission: List<String?>?)
    }

    object Constants {
        const val VIDEO_TYPE = "video/*"
        const val AUDIO_TYPE = "audio/*"
        const val KEY_VIDEO_EXTRA = "video_path"
        const val KEY_VIDEO_ARRAY_EXTRA = "video_array"
        const val REQUEST_VIDEO = 1
        const val REQUEST_AUDIO = 2

        const val REQUEST_CODE = 1 //用于运行时权限请求的请求码
    }

    private fun requestPermission() {
        requestRuntimePermission(
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ), object : PermissionListener {
                override fun onGranted() {
                }

                override fun onDenied(deniedPermission: List<String?>?) {
                    Toast.makeText(this@TimeLineActivity, "拒绝权限", Toast.LENGTH_SHORT).show()
                    finish()
                }
            })
    }

    override fun onDestroy() {
        binding.rvFrame.release()
        super.onDestroy()
    }

}