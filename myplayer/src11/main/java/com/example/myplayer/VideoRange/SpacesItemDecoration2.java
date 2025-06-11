package com.example.myplayer.VideoRange;

import android.graphics.Rect;
import android.util.Log;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

public class SpacesItemDecoration2 extends RecyclerView.ItemDecoration{

  private int space;
  private int thumbnailsCount;

  public SpacesItemDecoration2(int space, int thumbnailsCount) {
    this.space = space;
    this.thumbnailsCount = thumbnailsCount;
  }

  @Override
  public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
    int position = parent.getChildAdapterPosition(view);
    if (position == 0) {
      outRect.left = space;
      outRect.right = 0;
    } else if (thumbnailsCount > 1 && position == thumbnailsCount - 1) {
      outRect.left = 0;
      outRect.right = 0;
    } else {
      outRect.left = 0;
      outRect.right = 0;
    }
  }
}
