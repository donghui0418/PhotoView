package test.zoomabledrawee;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Animatable;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.controller.BaseControllerListener;
import com.facebook.drawee.generic.GenericDraweeHierarchy;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.image.ImageInfo;

public class PhotoDraweeView extends SimpleDraweeView {

    public static final String TAG = "PhotoDraweeView";
    private Attacher mAttacher;

    public PhotoDraweeView(Context context, GenericDraweeHierarchy hierarchy) {
        super(context, hierarchy);
        init();
    }

    public PhotoDraweeView(Context context) {
        super(context);
        init();
    }

    public PhotoDraweeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PhotoDraweeView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    protected void init() {
        if (mAttacher == null || mAttacher.getDraweeView() == null) {
            mAttacher = new Attacher(this);
        }
    }

    public Attacher getAttacher() {
        return mAttacher;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    @Override protected void onDraw(@NonNull Canvas canvas) {
        int saveCount = canvas.save();
        if (mAttacher.isEnableDraweeMatrix()) {
            canvas.concat(mAttacher.getDrawMatrix());
        }
        super.onDraw(canvas);
        canvas.restoreToCount(saveCount);
    }

    @Override protected void onAttachedToWindow() {
        init();
        super.onAttachedToWindow();
    }

    @Override protected void onDetachedFromWindow() {
        mAttacher.onDetachedFromWindow();
        super.onDetachedFromWindow();
    }

    @Override public void setOnLongClickListener(OnLongClickListener listener) {
        mAttacher.setOnLongClickListener(listener);
    }

    public float getScale() {
        return mAttacher.getScale();
    }

    public float getMinimumScale() {
        return mAttacher.getCompensatedMinScale();
    }

    public float getMaximumScale() {
        return mAttacher.getCompensatedMaxScale();
    }

    public float getScaleAtLevel(int level) {
        return mAttacher.getCompensateScaleAtLevel(level);
    }

    public int getParentTakeOverPolicy() {
        return mAttacher.getParentTakeOverPolicy();
    }

    public void setParentTakeOverPolicy(int mParentTakeOverPolicy) {
        mAttacher.setParentTakeOverPolicy(mParentTakeOverPolicy);
    }

    public void rotateClockwise(boolean animate) {
        mAttacher.rotate90Degrees(true, animate);
    }

    public void rotateCounterclockwise(boolean animate) {
        mAttacher.rotate90Degrees(false, animate);
    }

    public void setScale(float scale) {
        mAttacher.setScale(scale);
    }

    public void setScale(float scale, boolean animate) {
        mAttacher.setScale(scale, animate);
    }

    public void setScale(float scale, float focalX, float focalY, boolean animate) {
        mAttacher.setScale(scale, focalX, focalY, animate);
    }

    public void clearScaleEffect() {
        mAttacher.clearScaleEffect();
    }

    public void setZoomTransitionDuration(int duration) {
        mAttacher.setZoomTransitionDuration(duration);
    }

    public void setAllowParentInterceptOnEdge(boolean allow) {
        mAttacher.setAllowParentInterceptOnEdge(allow);
    }

    public void setAllowRotateInAnyScale(boolean allow) {
        mAttacher.setAllowRotateInAnyScale(allow);
    }

    public void setOnDoubleTapListener(GestureDetector.OnDoubleTapListener listener) {
        mAttacher.setOnDoubleTapListener(listener);
    }

    public void setOnScaleChangeListener(OnScaleChangeListener listener) {
        mAttacher.setOnScaleChangeListener(listener);
    }

    public void setOnPhotoTapListener(OnPhotoTapListener listener) {
        mAttacher.setOnPhotoTapListener(listener);
    }

    public void setOnViewTapListener(OnViewTapListener listener) {
        mAttacher.setOnViewTapListener(listener);
    }

    public OnPhotoTapListener getOnPhotoTapListener() {
        return mAttacher.getOnPhotoTapListener();
    }

    public OnViewTapListener getOnViewTapListener() {
        return mAttacher.getOnViewTapListener();
    }

    public void update(int imageInfoWidth, int imageInfoHeight) {
        mAttacher.update(imageInfoWidth, imageInfoHeight);
    }

    public boolean isEnableDraweeMatrix() {
        return mAttacher.isEnableDraweeMatrix();
    }

    public void setEnableDraweeMatrix(boolean enableDraweeMatrix) {
        mAttacher.setEnableDraweeMatrix(enableDraweeMatrix);
    }

    public void setPhotoUri(Uri uri) {
        setPhotoUri(uri, null);
    }

    public void setPhotoUri(Uri uri, @Nullable Context context) {
        DraweeController controller = Fresco.newDraweeControllerBuilder()
                .setCallerContext(context)
                .setUri(uri)
                .setOldController(getController())
                .setControllerListener(new BaseControllerListener<ImageInfo>() {
                    @Override public void onFailure(String id, Throwable throwable) {
                        super.onFailure(id, throwable);
                        setEnableDraweeMatrix(false);
                    }

                    @Override public void onFinalImageSet(String id, ImageInfo imageInfo,
                                                          Animatable animatable) {
                        super.onFinalImageSet(id, imageInfo, animatable);
                        setEnableDraweeMatrix(true);
                        if (imageInfo != null) {
                            update(imageInfo.getWidth(), imageInfo.getHeight());
                        }
                    }

                    @Override
                    public void onIntermediateImageFailed(String id, Throwable throwable) {
                        super.onIntermediateImageFailed(id, throwable);
                        setEnableDraweeMatrix(false);
                    }

                    @Override public void onIntermediateImageSet(String id, ImageInfo imageInfo) {
                        super.onIntermediateImageSet(id, imageInfo);
                        setEnableDraweeMatrix(true);
                        if (imageInfo != null) {
                            update(imageInfo.getWidth(), imageInfo.getHeight());
                        }
                    }
                })
                .build();
        setController(controller);
    }
}