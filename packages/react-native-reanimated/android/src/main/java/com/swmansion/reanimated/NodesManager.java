package com.swmansion.reanimated;

import static java.lang.Float.NaN;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.Trace;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.GuardedRunnable;
import com.facebook.react.bridge.JavaOnlyMap;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.UIManager;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.ReactChoreographer;
import com.facebook.react.uimanager.GuardedFrameCallback;
import com.facebook.react.uimanager.IllegalViewOperationException;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.uimanager.ReactShadowNode;
import com.facebook.react.uimanager.ReactStylesDiffMap;
import com.facebook.react.uimanager.RootView;
import com.facebook.react.uimanager.RootViewUtil;
import com.facebook.react.uimanager.UIImplementation;
import com.facebook.react.uimanager.UIManagerHelper;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.UIManagerReanimatedHelper;
import com.facebook.react.uimanager.common.UIManagerType;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.uimanager.events.EventDispatcherListener;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.swmansion.reanimated.layoutReanimation.AnimationsManager;
import com.swmansion.reanimated.nativeProxy.NoopEventHandler;
import com.swmansion.worklets.WorkletsModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

public class NodesManager implements EventDispatcherListener {

  private Long mFirstUptime = SystemClock.uptimeMillis();
  private boolean mSlowAnimationsEnabled = false;
  private int mAnimationsDragFactor;

  public void scrollTo(int viewTag, double x, double y, boolean animated) {
    View view;
    try {
      view = mUIManager.resolveView(viewTag);
    } catch (IllegalViewOperationException e) {
      e.printStackTrace();
      return;
    }
    NativeMethodsHelper.scrollTo(view, x, y, animated);
  }

  public void dispatchCommand(int viewTag, String commandId, ReadableArray commandArgs) {
    // mUIManager.dispatchCommand must be called from native modules queue thread
    // because of an assert in ShadowNodeRegistry.getNode
    mContext.runOnNativeModulesQueueThread(
        new GuardedRunnable(mContext.getExceptionHandler()) {
          @Override
          public void runGuarded() {
            mUIManager.dispatchCommand(viewTag, commandId, commandArgs);
          }
        });
  }

  public float[] measure(int viewTag) {
    View view;
    try {
      view = mUIManager.resolveView(viewTag);
    } catch (IllegalViewOperationException e) {
      e.printStackTrace();
      return (new float[] {NaN, NaN, NaN, NaN, NaN, NaN});
    }
    return NativeMethodsHelper.measure(view);
  }

  public interface OnAnimationFrame {
    void onAnimationFrame(double timestampMs);
  }

  private final WorkletsModule mWorkletsModule;
  private final AnimationsManager mAnimationManager;
  private final UIImplementation mUIImplementation;
  private final DeviceEventManagerModule.RCTDeviceEventEmitter mEventEmitter;
  private final ReactChoreographer mReactChoreographer;
  private final GuardedFrameCallback mChoreographerCallback;
  protected final UIManagerModule.CustomEventNamesResolver mCustomEventNamesResolver;
  private final AtomicBoolean mCallbackPosted = new AtomicBoolean();
  private final AtomicBoolean mIsHostPaused = new AtomicBoolean();
  private final ReactContext mContext;
  private final UIManager mUIManager;
  private RCTEventEmitter mCustomEventHandler = new NoopEventHandler();
  private List<OnAnimationFrame> mFrameCallbacks = new ArrayList<>();
  private ConcurrentLinkedQueue<CopiedEvent> mEventQueue = new ConcurrentLinkedQueue<>();
  private double lastFrameTimeMs;
  public Set<String> uiProps = Collections.emptySet();
  public Set<String> nativeProps = Collections.emptySet();
  private ReaCompatibility compatibility;
  private @Nullable Runnable mUnsubscribe = null;

  private boolean isPerformOperationsActive;

  public boolean isPerformOperationsActive() {
    return isPerformOperationsActive;
  }

  public NativeProxy getNativeProxy() {
    return mNativeProxy;
  }

  private NativeProxy mNativeProxy;

  private DrawPassDetector mDrawPassDetector;

  public AnimationsManager getAnimationsManager() {
    return mAnimationManager;
  }

  public void invalidate() {
    if (mAnimationManager != null) {
      mAnimationManager.invalidate();
    }

    if (mNativeProxy != null) {
      mNativeProxy.invalidate();
      mNativeProxy = null;
    }

    if (mDrawPassDetector != null) {
      mDrawPassDetector.invalidate();
      mDrawPassDetector = null;
    }

    if (compatibility != null) {
      compatibility.unregisterFabricEventListener(this);
    }

    if (mUnsubscribe != null) {
      mUnsubscribe.run();
      mUnsubscribe = null;
    }
  }

  public void initWithContext(ReactApplicationContext reactApplicationContext) {
    mDrawPassDetector = new DrawPassDetector(reactApplicationContext);
    mNativeProxy = new NativeProxy(reactApplicationContext, mWorkletsModule);
    mAnimationManager.setAndroidUIScheduler(mWorkletsModule.getAndroidUIScheduler());
    compatibility = new ReaCompatibility(reactApplicationContext);
    compatibility.registerFabricEventListener(this);
  }

  private final class NativeUpdateOperation {
    public int mViewTag;
    public WritableMap mNativeProps;

    public NativeUpdateOperation(int viewTag, WritableMap nativeProps) {
      mViewTag = viewTag;
      mNativeProps = nativeProps;
    }
  }

  private Queue<NativeUpdateOperation> mOperationsInBatch = new LinkedList<>();
  private boolean mTryRunBatchUpdatesSynchronously = false;
  int count = 0;


  public NodesManager(ReactContext context, WorkletsModule workletsModule) {
    mContext = context;
    mWorkletsModule = workletsModule;
    int uiManagerType =
        BuildConfig.IS_NEW_ARCHITECTURE_ENABLED ? UIManagerType.FABRIC : UIManagerType.DEFAULT;
    mUIManager = UIManagerHelper.getUIManager(context, uiManagerType);
    assert mUIManager != null;
    mUIImplementation =
        mUIManager instanceof UIManagerModule
            ? ((UIManagerModule) mUIManager).getUIImplementation()
            : null;
    mCustomEventNamesResolver = mUIManager::resolveCustomDirectEventName;
    mEventEmitter = context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);

    mReactChoreographer = ReactChoreographer.getInstance();
    mChoreographerCallback =
        new GuardedFrameCallback(context) {
          @Override
          protected void doFrameGuarded(long frameTimeNanos) {
            onAnimationFrame(frameTimeNanos);
          }
        };

    if (!BuildConfig.IS_NEW_ARCHITECTURE_ENABLED) {
      // We register as event listener at the end, because we pass `this` and we haven't finished
      // constructing an object yet.
      // This lead to a crash described in
      // https://github.com/software-mansion/react-native-reanimated/issues/604 which was caused by
      // Nodes Manager being constructed on UI thread and registering for events.
      // Events are handled in the native modules thread in the `onEventDispatch()` method.
      // This method indirectly uses `mChoreographerCallback` which was created after event
      // registration, creating race condition
      EventDispatcher eventDispatcher =
          Objects.requireNonNull(UIManagerHelper.getEventDispatcher(context, uiManagerType));
      eventDispatcher.addListener(this);
      mUnsubscribe = () -> eventDispatcher.removeListener(this);
    }

    mAnimationManager = new AnimationsManager(mContext, mUIManager);
  }

  public void onHostPause() {
    mIsHostPaused.set(true);
    if (mCallbackPosted.get()) {
      stopUpdatingOnAnimationFrame();
      mCallbackPosted.set(true);
    }
  }

  public boolean isAnimationRunning() {
    return mCallbackPosted.get();
  }

  public void onHostResume() {
    mIsHostPaused.set(false);
    if (mCallbackPosted.getAndSet(false)) {
      startUpdatingOnAnimationFrame();
    }
  }

  public void startUpdatingOnAnimationFrame() {
    /**
     * Discord stuff: While we're paused, the update should not run.
     *
     * While the app is paused (e.g. we just opened a permission dialog), Fabric may not mount views onto the screen.
     * This means that if we are trying to animate the view (e.g. the Voice Panel, while the Mic permission is open),
     * the update will fail. This can cause the view to remain stuck forever. We simply skip the runs, until we resume.
     *
     * (We actually throw some RetryableMountingLayerException in these cases in the logs).
     *
     * Also, just for clarity, `mIsHostPaused` is purposely checked after the get-and-set so that
     * `onHostResume` will properly re-trigger this again if `startUpdatingOnAnimationFrame`
     * was called during the pause.
     */
    if (!mCallbackPosted.getAndSet(true) && !mIsHostPaused.get()) {
      mReactChoreographer.postFrameCallback(
          ReactChoreographer.CallbackType.NATIVE_ANIMATED_MODULE, mChoreographerCallback);
    }
  }

  private void stopUpdatingOnAnimationFrame() {
    if (mCallbackPosted.getAndSet(false)) {
      mReactChoreographer.removeFrameCallback(
          ReactChoreographer.CallbackType.NATIVE_ANIMATED_MODULE, mChoreographerCallback);
    }
  }

  int scheduled = 0;
  Handler mainHandler = new Handler(Looper.getMainLooper());

  @androidx.annotation.UiThread
  public void performOperations(boolean isTriggeredByEvent, boolean isDrawing) {
    if (BuildConfig.IS_NEW_ARCHITECTURE_ENABLED) {
      if (mNativeProxy != null) {
          isPerformOperationsActive = true;
          mNativeProxy.performOperations(isTriggeredByEvent, /* mountSync */ !isDrawing);
          isPerformOperationsActive = false;
      }
    } else if (!mOperationsInBatch.isEmpty()) {
      final Queue<NativeUpdateOperation> copiedOperationsQueue = mOperationsInBatch;
      mOperationsInBatch = new LinkedList<>();
      final boolean trySynchronously = mTryRunBatchUpdatesSynchronously;
      mTryRunBatchUpdatesSynchronously = false;
      final Semaphore semaphore = new Semaphore(0);
      mContext.runOnNativeModulesQueueThread(
          new GuardedRunnable(mContext.getExceptionHandler()) {
            @Override
            public void runGuarded() {
              boolean queueWasEmpty =
                  UIManagerReanimatedHelper.isOperationQueueEmpty(mUIImplementation);
              boolean shouldDispatchUpdates = trySynchronously && queueWasEmpty;
              if (!shouldDispatchUpdates) {
                semaphore.release();
              }
              while (!copiedOperationsQueue.isEmpty()) {
                NativeUpdateOperation op = copiedOperationsQueue.remove();
                ReactShadowNode<?> shadowNode = mUIImplementation.resolveShadowNode(op.mViewTag);
                if (shadowNode != null) {
                  ((UIManagerModule) mUIManager)
                      .updateView(op.mViewTag, shadowNode.getViewClass(), op.mNativeProps);
                }
              }
              if (queueWasEmpty) {
                mUIImplementation.dispatchViewUpdates(-1); // no associated batchId
              }
              if (shouldDispatchUpdates) {
                semaphore.release();
              }
            }
          });
      if (trySynchronously) {
        try {
          boolean ignored = semaphore.tryAcquire(16, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          // if the thread is interrupted we just continue and let the layout update happen
          // asynchronously
        }
      }
    }
  }

  private void onAnimationFrame(long frameTimeNanos) {
    // Trace.beginSection("onAnimationFrame");

    double currentFrameTimeMs = frameTimeNanos / 1000000.;
    if (mSlowAnimationsEnabled) {
      currentFrameTimeMs =
          mFirstUptime + (currentFrameTimeMs - mFirstUptime) / mAnimationsDragFactor;
    }

    if (currentFrameTimeMs > lastFrameTimeMs) {
      // It is possible for ChoreographerCallback to be executed twice within the same frame
      // due to frame drops. If this occurs, the additional callback execution should be ignored.
      lastFrameTimeMs = currentFrameTimeMs;

      while (!mEventQueue.isEmpty()) {
        CopiedEvent copiedEvent = mEventQueue.poll();
        handleEvent(
            copiedEvent.getTargetTag(), copiedEvent.getEventName(), copiedEvent.getPayload());
      }

      if (!mFrameCallbacks.isEmpty()) {
        List<OnAnimationFrame> frameCallbacks = mFrameCallbacks;
        mFrameCallbacks = new ArrayList<>(frameCallbacks.size());
        for (int i = 0, size = frameCallbacks.size(); i < size; i++) {
          frameCallbacks.get(i).onAnimationFrame(currentFrameTimeMs);
        }
      }

      performOperations(false, false);
    }

    mCallbackPosted.set(false);
    if (!mFrameCallbacks.isEmpty() || !mEventQueue.isEmpty()) {
      // enqueue next frame
      startUpdatingOnAnimationFrame();
    }

    // Trace.endSection();
  }

  public void enqueueUpdateViewOnNativeThread(
      int viewTag, WritableMap nativeProps, boolean trySynchronously) {
    if (trySynchronously) {
      mTryRunBatchUpdatesSynchronously = true;
    }
    mOperationsInBatch.add(new NativeUpdateOperation(viewTag, nativeProps));
  }

  public void configureProps(Set<String> uiPropsSet, Set<String> nativePropsSet) {
    uiProps = uiPropsSet;
    nativeProps = nativePropsSet;
  }

  public void postOnAnimation(OnAnimationFrame onAnimationFrame) {
    mFrameCallbacks.add(onAnimationFrame);
    startUpdatingOnAnimationFrame();
  }

  @Override
  public void onEventDispatch(Event event) {
    if (mNativeProxy == null) {
      return;
    }
    // Events can be dispatched from any thread so we have to make sure handleEvent is run from the
    // UI thread.
    if (UiThreadUtil.isOnUiThread()) {
      handleEvent(event);
      /*
       * Discord edit:
       * Directly calling performOperations will cause animation to run immediately which helps e.g.
       * when animating gestures that need to be updated in the same frame. So this schedules a sync react commit & mount.
       * The problem is that this happens for _every_ event in our whole app. However, we only really need this for
       * events that we dispatch to JS, which update a shared value, which is used in something like useAnimatedStyle
       * to update the UI in that very frame. As a rule of thumb this is true for libraries using
       * [useEvent](https://docs.swmansion.com/react-native-reanimated/docs/advanced/useEvent/) (e.g. RNGH).
       *
       * In discord there are as of writing this only three such libraries that need sync events:
       * - react-native-gesture-handler
       * - react-native-keyboard-controller (sync animations)
       * - react-native ScrollView (sync scroll events)
       *
       * Now, we only enable perform operations directly for distinct events and explicitly _not_ for RNKC.
       * Why:
       *   There is a condition that can cause a crash in RNKC, where its dispatched event will trigger
       *   a reanimated height change, which is a layout change, while we are in the middle of a preDraw phase.
       *   See this ticket for details: https://app.asana.com/1/236888843494340/project/1199705967702853/task/1210922776998968
       *
       *   Overall, this isn't terrible as reanimated will schedule the UI update for the next frame.
       *   Opening the keyboard is a fast animation so a potentially missed frame isn't that noticeable.
       *
       * Additionally only enabling this for events that really need it is a good performance optimization.
       * Otherwise we might execute multiple updates per frame, which can lead to frame jank.
       */
      String eventName = event.getEventName();
      if (eventName.contains("GestureHandler") || eventName.contains("Scroll")) {
        if (mDrawPassDetector != null) {
          mDrawPassDetector.initialize();
        }
        boolean isInDrawPass = mDrawPassDetector != null && mDrawPassDetector.isInDrawPass();
        performOperations(true, isInDrawPass);
        // Note(@hannojg): there has been a new edit in
        // https://github.com/software-mansion/react-native-reanimated/pull/8459
        // This will prevent to run scheduled layout animation synchronously here when triggered by
        // event.
        // The reason is because events can happen during drawing, see an example here:
        // https://discord.sentry.io/issues/6167554844/events/e489ad9bb6a244589b5d9c9e2f41855c/?project=5992375&referrer=previous-event
        // *However*, this only prevents sync renders from LA. I am not 100% convinced if this is
        // enough to prevent all possible crashes.
        // Because if not a LA there is a furhter code path that can also lead to sync renders.
      }
    } else {
      String eventName = mCustomEventNamesResolver.resolveCustomEventName(event.getEventName());
      int viewTag = event.getViewTag();
      boolean shouldSaveEvent = mNativeProxy.isAnyHandlerWaitingForEvent(eventName, viewTag);
      if (shouldSaveEvent) {
        mEventQueue.offer(new CopiedEvent(event));
      }
      startUpdatingOnAnimationFrame();
    }
  }

  private void handleEvent(Event event) {
    event.dispatch(mCustomEventHandler);
  }

  private void handleEvent(int targetTag, String eventName, @Nullable WritableMap event) {
    mCustomEventHandler.receiveEvent(targetTag, eventName, event);
  }

  public UIManagerModule.CustomEventNamesResolver getEventNameResolver() {
    return mCustomEventNamesResolver;
  }

  public void registerEventHandler(RCTEventEmitter handler) {
    mCustomEventHandler = handler;
  }

  public void sendEvent(String name, WritableMap body) {
    mEventEmitter.emit(name, body);
  }

  public void updateProps(int viewTag, Map<String, Object> props) {
    /*
     * This is a temporary fix intended to address an issue where updates to properties
     * are attempted on views that may not exist or have been removed. This scenario can
     * occur in fast-changing UI environments where components are frequently added or
     * removed, leading to potential inconsistencies or errors when attempting to update
     * views based on outdated references
     */
    try {
      View view = mUIManager.resolveView(viewTag);
      if (view == null) {
        return;
      }
    } catch (IllegalViewOperationException e) {
      return;
    }

    // TODO: update PropsNode to use this method instead of its own way of updating props
    boolean hasUIProps = false;
    boolean hasNativeProps = false;
    boolean hasJSProps = false;
    JavaOnlyMap newUIProps = new JavaOnlyMap();
    WritableMap newJSProps = Arguments.createMap();
    WritableMap newNativeProps = Arguments.createMap();

    for (Map.Entry<String, Object> entry : props.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (uiProps.contains(key)) {
        hasUIProps = true;
        addProp(newUIProps, key, value);
      } else if (nativeProps.contains(key)) {
        hasNativeProps = true;
        addProp(newNativeProps, key, value);
      } else {
        hasJSProps = true;
        addProp(newJSProps, key, value);
      }
    }

    if (viewTag != View.NO_ID) {
      if (hasUIProps) {
        mUIImplementation.synchronouslyUpdateViewOnUIThread(
            viewTag, new ReactStylesDiffMap(newUIProps));
      }
      if (hasNativeProps) {
        enqueueUpdateViewOnNativeThread(viewTag, newNativeProps, true);
      }
      if (hasJSProps) {
        WritableMap evt = Arguments.createMap();
        evt.putInt("viewTag", viewTag);
        evt.putMap("props", newJSProps);
        sendEvent("onReanimatedPropsChange", evt);
      }
    }
  }

  public void synchronouslyUpdateUIProps(int viewTag, ReadableMap uiProps) {
    compatibility.synchronouslyUpdateUIProps(viewTag, uiProps);
  }

  public String obtainProp(int viewTag, String propName) {
    View view;
    try {
      view = mUIManager.resolveView(viewTag);
    } catch (Exception e) {
      // This happens when the view is not mounted yet
      return "[Reanimated] Unable to resolve view";
    }

    switch (propName) {
      case "opacity" -> {
        return Float.toString(view.getAlpha());
      }
      case "zIndex" -> {
        return Float.toString(view.getElevation());
      }
      case "width" -> {
        return Float.toString(PixelUtil.toDIPFromPixel(view.getWidth()));
      }
      case "height" -> {
        return Float.toString(PixelUtil.toDIPFromPixel(view.getHeight()));
      }
      case "top" -> {
        return Float.toString(PixelUtil.toDIPFromPixel(view.getTop()));
      }
      case "left" -> {
        return Float.toString(PixelUtil.toDIPFromPixel(view.getLeft()));
      }
      case "backgroundColor" -> {
        Drawable background = view.getBackground();
        try {
          Method getColor = background.getClass().getMethod("getColor");
          int actualColor = (int) getColor.invoke(background);

          String invertedColor = String.format("%08x", (0xFFFFFFFF & actualColor));
          // By default transparency is first, color second
          return "#" + invertedColor.substring(2, 8) + invertedColor.substring(0, 2);

        } catch (Exception e) {
          return "Unable to resolve background color";
        }
      }
      default -> {
        throw new IllegalArgumentException(
            "[Reanimated] Attempted to get unsupported property "
                + propName
                + " with function `getViewProp`");
      }
    }
  }

  private static WritableMap copyReadableMap(ReadableMap map) {
    WritableMap copy = Arguments.createMap();
    copy.merge(map);
    return copy;
  }

  private static WritableArray copyReadableArray(ReadableArray array) {
    WritableArray copy = Arguments.createArray();
    for (int i = 0; i < array.size(); i++) {
      ReadableType type = array.getType(i);
      switch (type) {
        case Boolean -> copy.pushBoolean(array.getBoolean(i));
        case String -> copy.pushString(array.getString(i));
        case Null -> copy.pushNull();
        case Number -> copy.pushDouble(array.getDouble(i));
        case Map -> copy.pushMap(copyReadableMap(array.getMap(i)));
        case Array -> copy.pushArray(copyReadableArray(array.getArray(i)));
        default -> throw new IllegalStateException("[Reanimated] Unknown type of ReadableArray.");
      }
    }
    return copy;
  }

  private static void addProp(WritableMap propMap, String key, Object value) {
    if (value == null) {
      propMap.putNull(key);
    } else if (value instanceof Double) {
      propMap.putDouble(key, (Double) value);
    } else if (value instanceof Integer) {
      propMap.putInt(key, (Integer) value);
    } else if (value instanceof Number) {
      propMap.putDouble(key, ((Number) value).doubleValue());
    } else if (value instanceof Boolean) {
      propMap.putBoolean(key, (Boolean) value);
    } else if (value instanceof String) {
      propMap.putString(key, (String) value);
    } else if (value instanceof ReadableArray) {
      if (!(value instanceof WritableArray)) {
        propMap.putArray(key, copyReadableArray((ReadableArray) value));
      } else {
        propMap.putArray(key, (ReadableArray) value);
      }
    } else if (value instanceof ReadableMap) {
      if (!(value instanceof WritableMap)) {
        propMap.putMap(key, copyReadableMap((ReadableMap) value));
      } else {
        propMap.putMap(key, (ReadableMap) value);
      }
    } else {
      throw new IllegalStateException("[Reanimated] Unknown type of animated value.");
    }
  }

  public void enableSlowAnimations(boolean slowAnimationsEnabled, int animationsDragFactor) {
    mSlowAnimationsEnabled = slowAnimationsEnabled;
    mAnimationsDragFactor = animationsDragFactor;
    if (slowAnimationsEnabled) {
      mFirstUptime = SystemClock.uptimeMillis();
    }
  }
}
