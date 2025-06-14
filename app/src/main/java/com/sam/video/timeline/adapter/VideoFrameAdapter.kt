package com.sam.video.timeline.adapter

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import com.btbtech.myvtv.R
import com.bumptech.glide.Glide
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.sam.video.timeline.bean.VideoFrameData
import com.sam.video.timeline.widget.RoundRectMask

/**
 * 帧列表 adapter
 * @author SamWang(33691286@qq.com)
 * @date 2019-07-19
 */
class VideoFrameAdapter(data: MutableList<VideoFrameData>, private val frameWidth: Int) : BaseQuickAdapter<VideoFrameData, BaseViewHolder>(
    R.layout.item_video_frame, data) {


    override fun convert(helper: BaseViewHolder, item: VideoFrameData) {

        val imageView = helper.getView<ImageView>(R.id.iv)
        val layoutParams = helper.itemView.layoutParams
        layoutParams.width = item.frameWidth

        val maskView = helper.getView<RoundRectMask>(R.id.mask)
        maskView.setCornerRadiusDp(4f)
        maskView.setCorners(item.isFirstItem, item.isLastItem, item.isFirstItem, item.isLastItem)
        val maskLayoutParams = maskView.layoutParams as FrameLayout.LayoutParams
        val ivLayoutParams = imageView.layoutParams as FrameLayout.LayoutParams

        if (item.isFirstItem) {
            maskLayoutParams.gravity = Gravity.LEFT
            ivLayoutParams.gravity = Gravity.RIGHT
        } else {
            maskLayoutParams.gravity = Gravity.RIGHT
            ivLayoutParams.gravity = Gravity.LEFT
        }

        maskLayoutParams.width = if (item.isFirstItem && item.isLastItem) {
            ivLayoutParams.gravity = Gravity.LEFT
            ivLayoutParams.marginStart = -item.offsetX //只有一帧考虑位移

            item.frameWidth             //如果一段视频在列表中只有一帧，则要显示全部圆角，遮罩同步缩小
        } else {
            ivLayoutParams.marginStart = 0
            frameWidth
        }

        Glide.with(imageView)
            .asBitmap()
            .load(item.videoData.originalFilePath)
            .frame(item.frameClipTime * 1000)
            .thumbnail(
                //todo 更好的方案是往前找一个已经有的缓存帧
                Glide.with(imageView).asBitmap().load(item.videoData.originalFilePath)
            )
            .into(imageView)
    }
}




