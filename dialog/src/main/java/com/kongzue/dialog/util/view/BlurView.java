package com.kongzue.dialog.util.view;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.support.v8.renderscript.Allocation;
import android.support.v8.renderscript.Element;
import android.support.v8.renderscript.RenderScript;
import android.support.v8.renderscript.ScriptIntrinsicBlur;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;

import com.kongzue.dialog.R;

public class BlurView extends View {
    private float mDownsampleFactor; // default 4
    private int mOverlayColor; // default #aaffffff
    private float mBlurRadius; // default 10dp (0 < r <= 25)
    
    private boolean mDirty;
    private Bitmap mBitmapToBlur, mBlurredBitmap;
    private Canvas mBlurringCanvas;
    private RenderScript mRenderScript;
    private ScriptIntrinsicBlur mBlurScript;
    private Allocation mBlurInput, mBlurOutput;
    private boolean mIsRendering;
    private final Rect mRectSrc = new Rect(), mRectDst = new Rect();
    // mDecorView should be the root view of the activity (even if you are on a different window like a dialog)
    private View mDecorView;
    // If the view is on different root view (usually means we are on a PopupWindow),
    // we need to manually call invalidate() in onPreDraw(), otherwise we will not be able to see the changes
    private boolean mDifferentRoot;
    private static int RENDERING_COUNT;
    
    private Paint mPaint;
    private RectF mRectF;
    private float mXRadius;
    private float mYRadius;
    
    private Bitmap mRoundBitmap;
    private Canvas mTmpCanvas;
    
    public BlurView(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RealtimeBlurView);
        mBlurRadius = a.getDimension(
                R.styleable.RealtimeBlurView_realtimeBlurRadius,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 35, context.getResources().getDisplayMetrics())
        );
        mDownsampleFactor = a.getFloat(R.styleable.RealtimeBlurView_realtimeDownsampleFactor, 4);
        mOverlayColor = a.getColor(R.styleable.RealtimeBlurView_realtimeOverlayColor, 0x00ffffff);
        
        //ready rounded corner
        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mRectF = new RectF();
        
        mXRadius = a.getDimension(R.styleable.RealtimeBlurView_xRadius, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15, context.getResources().getDisplayMetrics()));
        mYRadius = a.getDimension(R.styleable.RealtimeBlurView_yRadius, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15, context.getResources().getDisplayMetrics()));
        a.recycle();
    }
    
    public void setBlurRadius(float radius) {
        if (mBlurRadius != radius) {
            mBlurRadius = radius;
            mDirty = true;
            invalidate();
        }
    }
    
    public void setRadius(Context context, float x, float y) {
        if (mXRadius != x || mYRadius != y) {
            mXRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, context.getResources().getDisplayMetrics());
            mYRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, y, context.getResources().getDisplayMetrics());
            mDirty = true;
            invalidate();
        }
    }
    
    public void setDownsampleFactor(float factor) {
        if (factor <= 0) {
            throw new IllegalArgumentException("Downsample factor must be greater than 0.");
        }
        
        if (mDownsampleFactor != factor) {
            mDownsampleFactor = factor;
            mDirty = true; // may also change blur radius
            releaseBitmap();
            invalidate();
        }
    }
    
    public void setOverlayColor(int color) {
        if (mOverlayColor != color) {
            mOverlayColor = color;
            invalidate();
        }
    }
    
    private void releaseBitmap() {
        if (mBlurInput != null) {
            mBlurInput.destroy();
            mBlurInput = null;
        }
        if (mBlurOutput != null) {
            mBlurOutput.destroy();
            mBlurOutput = null;
        }
        if (mBitmapToBlur != null) {
            mBitmapToBlur.recycle();
            mBitmapToBlur = null;
        }
        if (mBlurredBitmap != null) {
            mBlurredBitmap.recycle();
            mBlurredBitmap = null;
        }
    }
    
    private void releaseScript() {
        if (mRenderScript != null) {
            mRenderScript.destroy();
            mRenderScript = null;
        }
        if (mBlurScript != null) {
            mBlurScript.destroy();
            mBlurScript = null;
        }
    }
    
    protected void release() {
        releaseBitmap();
        releaseScript();
    }
    
    protected boolean prepare() {
        if (mBlurRadius == 0) {
            release();
            return false;
        }
        
        float downsampleFactor = mDownsampleFactor;
        
        if (mDirty || mRenderScript == null) {
            if (mRenderScript == null) {
                try {
                    mRenderScript = RenderScript.create(getContext());
                    mBlurScript = ScriptIntrinsicBlur.create(mRenderScript, Element.U8_4(mRenderScript));
                } catch (android.support.v8.renderscript.RSRuntimeException e) {
                    if (isDebug(getContext())) {
                        if (e.getMessage() != null && e.getMessage().startsWith("Error loading RS jni library: java.lang.UnsatisfiedLinkError:")) {
                            throw new RuntimeException("Error loading RS jni library, Upgrade buildToolsVersion=\"24.0.2\" or higher may solve this issue");
                        } else {
                            throw e;
                        }
                    } else {
                        // In release mode, just ignore
                        releaseScript();
                        return false;
                    }
                }
            }
            
            mDirty = false;
            float radius = mBlurRadius / downsampleFactor;
            if (radius > 25) {
                downsampleFactor = downsampleFactor * radius / 25;
                radius = 25;
            }
            mBlurScript.setRadius(radius);
        }
        
        final int width = getWidth();
        final int height = getHeight();
        
        int scaledWidth = Math.max(1, (int) (width / downsampleFactor));
        int scaledHeight = Math.max(1, (int) (height / downsampleFactor));
        
        if (mBlurringCanvas == null || mBlurredBitmap == null
                || mBlurredBitmap.getWidth() != scaledWidth
                || mBlurredBitmap.getHeight() != scaledHeight) {
            releaseBitmap();
            
            boolean r = false;
            try {
                mBitmapToBlur = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
                if (mBitmapToBlur == null) {
                    return false;
                }
                mBlurringCanvas = new Canvas(mBitmapToBlur);
                
                mBlurInput = Allocation.createFromBitmap(mRenderScript, mBitmapToBlur,
                                                         Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT
                );
                mBlurOutput = Allocation.createTyped(mRenderScript, mBlurInput.getType());
                
                mBlurredBitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
                if (mBlurredBitmap == null) {
                    return false;
                }
                
                r = true;
            } catch (OutOfMemoryError e) {
                // Bitmap.createBitmap() may cause OOM error
                // Simply ignore and fallback
            } finally {
                if (!r) {
                    releaseBitmap();
                    return false;
                }
            }
        }
        return true;
    }
    
    protected void blur(Bitmap bitmapToBlur, Bitmap blurredBitmap) {
        mBlurInput.copyFrom(bitmapToBlur);
        mBlurScript.setInput(mBlurInput);
        mBlurScript.forEach(mBlurOutput);
        mBlurOutput.copyTo(blurredBitmap);
    }
    
    private final ViewTreeObserver.OnPreDrawListener preDrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            final int[] locations = new int[2];
            Bitmap oldBmp = mBlurredBitmap;
            View decor = mDecorView;
            if (decor != null && isShown() && prepare()) {
                boolean redrawBitmap = mBlurredBitmap != oldBmp;
                oldBmp = null;
                decor.getLocationOnScreen(locations);
                int x = -locations[0];
                int y = -locations[1];
                
                getLocationOnScreen(locations);
                x += locations[0];
                y += locations[1];
                
                // just erase transparent
                mBitmapToBlur.eraseColor(mOverlayColor & 0xffffff);
                
                int rc = mBlurringCanvas.save();
                mIsRendering = true;
                RENDERING_COUNT++;
                try {
                    mBlurringCanvas.scale(1.f * mBitmapToBlur.getWidth() / getWidth(), 1.f * mBitmapToBlur.getHeight() / getHeight());
                    mBlurringCanvas.translate(-x, -y);
                    if (decor.getBackground() != null) {
                        decor.getBackground().draw(mBlurringCanvas);
                    }
                    decor.draw(mBlurringCanvas);
                } catch (StopException e) {
                } finally {
                    mIsRendering = false;
                    RENDERING_COUNT--;
                    mBlurringCanvas.restoreToCount(rc);
                }
                
                blur(mBitmapToBlur, mBlurredBitmap);
                
                if (redrawBitmap || mDifferentRoot) {
                    invalidate();
                }
            }
            
            return true;
        }
    };
    
    protected View getActivityDecorView() {
        Context ctx = getContext();
        for (int i = 0; i < 4 && ctx != null && !(ctx instanceof Activity) && ctx instanceof ContextWrapper; i++) {
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        if (ctx instanceof Activity) {
            return ((Activity) ctx).getWindow().getDecorView();
        } else {
            return null;
        }
    }
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mDecorView = getActivityDecorView();
        if (mDecorView != null) {
            mDecorView.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
            mDifferentRoot = mDecorView.getRootView() != getRootView();
            if (mDifferentRoot) {
                mDecorView.postInvalidate();
            }
        } else {
            mDifferentRoot = false;
        }
    }
    
    @Override
    protected void onDetachedFromWindow() {
        if (mDecorView != null) {
            mDecorView.getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
        }
        release();
        super.onDetachedFromWindow();
    }
    
    @Override
    public void draw(Canvas canvas) {
        if (mIsRendering) {
            // Quit here, don't draw views above me
            throw STOP_EXCEPTION;
        } else if (RENDERING_COUNT > 0) {
            // Doesn't support blurview overlap on another blurview
        } else {
            super.draw(canvas);
        }
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawBlurredBitmap(canvas, mBlurredBitmap, mOverlayColor);
    }
    
    /**
     * Custom draw the blurred bitmap and color to define your own shape
     *
     * @param canvas
     * @param blurredBitmap
     * @param overlayColor
     */
    protected void drawBlurredBitmap(Canvas canvas, Bitmap blurredBitmap, int overlayColor) {
        if (blurredBitmap != null) {
            mRectSrc.right = blurredBitmap.getWidth();
            mRectSrc.bottom = blurredBitmap.getHeight();
            mRectDst.right = getWidth();
            mRectDst.bottom = getHeight();
            canvas.drawBitmap(blurredBitmap, mRectSrc, mRectDst, null);
        }
        canvas.drawColor(overlayColor);
        
        if (getWidth() > 0 && getHeight() > 0) {
            //Rounded corner
            mRectF.right = getWidth();
            mRectF.bottom = getHeight();
            if (mRoundBitmap == null) {
                mRoundBitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            }
            if (mTmpCanvas == null) {
                mTmpCanvas = new Canvas(mRoundBitmap);
            }
            mTmpCanvas.drawRoundRect(mRectF, mXRadius, mYRadius, mPaint);
        }
        
        mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        if (!mRoundBitmap.isRecycled()) canvas.drawBitmap(mRoundBitmap, 0, 0, mPaint);
    }
    
    private static class StopException extends RuntimeException {
    }
    
    private static StopException STOP_EXCEPTION = new StopException();
    
    static {
        try {
            BlurView.class.getClassLoader().loadClass("android.support.v8.renderscript.RenderScript");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("\n错误！\nRenderScript支持库未启用，要启用模糊效果，请在您的app的Gradle配置文件中添加以下语句：" +
                                               "\nandroid { \n...\n  defaultConfig { \n    ...\n    renderscriptTargetApi 19 \n    renderscriptSupportModeEnabled true \n  }\n}");
        }
    }
    
    // android:debuggable="true" in AndroidManifest.xml (auto set by build tool)
    static Boolean DEBUG = null;
    
    static boolean isDebug(Context ctx) {
        if (DEBUG == null && ctx != null) {
            DEBUG = (ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        }
        return DEBUG == Boolean.TRUE;
    }
}