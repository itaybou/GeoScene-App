package com.geoscene.maps.modules;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.ViewGroupManager;
import com.geoscene.ar.GeoARSceneFragment;
import com.geoscene.maps.MapsFragment;

import java.util.Map;
import java.util.Objects;

public class RNMapsViewManager extends ViewGroupManager<FrameLayout> {

    public static final String REACT_TAG = "MapView";
    private final ReactApplicationContext reactContext;
    private MapsFragment mapsFragment;

    public final int COMMAND_CREATE = 1;
    public final int COMMAND_DISPLAY = 2;

    public RNMapsViewManager(ReactApplicationContext reactContext) {
        super();
        this.reactContext = reactContext;
    }

    @NonNull
    @Override
    public String getName() {
        return REACT_TAG;
    }

    @SuppressLint("InflateParams")
    @NonNull
    @Override
    protected FrameLayout createViewInstance(@NonNull ThemedReactContext reactContext) {
        FrameLayout layout = new FrameLayout(reactContext);
        layout.setBackgroundColor(Color.BLACK);
        return layout;
    }

    @Nullable
    @Override
    public Map<String, Integer> getCommandsMap() {
        return MapBuilder.of(
                "CREATE", COMMAND_CREATE,
                "DISPLAY", COMMAND_DISPLAY
        );
    }

    @Override
    public void receiveCommand(@NonNull FrameLayout root, String commandId, @Nullable ReadableArray args) {
        Log.d(REACT_TAG,"command received: " + commandId + " view id: " + args);
        super.receiveCommand(root, commandId, args);
        int commandNo = Integer.parseInt(commandId);
        int arg;
        switch(commandNo) {
            case COMMAND_CREATE:
                arg = args.getInt(0);
                createMapsFragment(root, arg);
                break;
            case COMMAND_DISPLAY:
                arg = args.getInt(0);
                displayMapsFragment(root, arg);
                break;
            default:
                Log.w(REACT_TAG, "Invalid command recieved from ReactNative");
        }
    }

    private void displayMapsFragment(FrameLayout parentLayout, int reactNativeMapsViewId) {
        Log.d(REACT_TAG, Integer.toString(reactNativeMapsViewId));

        mapsFragment.zoomToBoundingBox();
    }

    private void createMapsFragment(FrameLayout parentLayout, int reactNativeMapsViewId) {

        ViewGroup parentView = (ViewGroup) parentLayout.findViewById(reactNativeMapsViewId).getParent();
        //organizeLayout(parentView);

//        final MapsFragment mapsFragment = new MapsFragment();
//        ((FragmentActivity) Objects.requireNonNull(this.reactContext.getCurrentActivity())).getSupportFragmentManager()
//                .beginTransaction()
//                .replace(reactNativeMapsViewId, mapsFragment, String.valueOf(reactNativeMapsViewId))
////                .replace(reactNativeMapsViewId, mapsFragment, String.valueOf(reactNativeMapsViewId))
//                .commit();


        //final MapsFragment mapsFragment = new MapsFragment();
        mapsFragment = new MapsFragment();

        ((FragmentActivity) Objects.requireNonNull(reactContext.getCurrentActivity())).getSupportFragmentManager()
                .beginTransaction()
                .replace(reactNativeMapsViewId, mapsFragment, String.valueOf(reactNativeMapsViewId))
//                .replace(reactNativeMapsViewId, mapsFragment, String.valueOf(reactNativeMapsViewId))
                .commit();

        addView(parentLayout, mapsFragment.getView(), ViewGroup.LayoutParams.MATCH_PARENT);

    }

//    @Nullable
//    @Override
//    public Map<String, Object> getExportedCustomBubblingEventTypeConstants() {
//        return super.getExportedCustomBubblingEventTypeConstants();
//    }
//
//    @Nullable
//    @Override
//    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
//        return MapBuilder.of(
//                "touchLocationMarker",
//                MapBuilder.of("registrationName", "onLocationMarkerTouch")
//        );
//    }

    private void organizeLayout(ViewGroup parentView) {
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                manuallyLayoutChildren(parentView);
                parentView.getViewTreeObserver().dispatchOnGlobalLayout();
                Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    private void manuallyLayoutChildren(ViewGroup view) {
        for(int i = 0; i < view.getChildCount(); i++) {
            View child = view.getChildAt(i);

            child.measure(View.MeasureSpec.makeMeasureSpec(view.getMeasuredWidth(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(view.getMeasuredHeight(), View.MeasureSpec.EXACTLY));
            child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
        }
    }
}
