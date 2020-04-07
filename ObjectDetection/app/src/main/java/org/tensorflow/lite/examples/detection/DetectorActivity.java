/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.detection;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.examples.detection.customview.OverlayView;
import org.tensorflow.lite.examples.detection.customview.OverlayView.DrawCallback;
import org.tensorflow.lite.examples.detection.env.BorderedText;
import org.tensorflow.lite.examples.detection.env.ImageUtils;
import org.tensorflow.lite.examples.detection.env.Logger;
import org.tensorflow.lite.examples.detection.tflite.Classifier;
import org.tensorflow.lite.examples.detection.tflite.TFLiteObjectDetectionAPIModel;
import org.tensorflow.lite.examples.detection.tracking.MultiBoxTracker;
import android.speech.tts.TextToSpeech;


/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {
  private TextToSpeech textToSpeech;
  ArrayList<String> labels = new ArrayList<String>();
  ArrayList<Integer> label_index = new ArrayList<Integer>();
//  ArrayList<String> prev_frame  = new ArrayList<String>();
  LinkedHashMap<String, Integer> tmp_object_map = new LinkedHashMap<String, Integer>();
  ArrayList<String> labels_array_to_speak = new ArrayList<>();
//  ArrayList<String> multiple_labels = new ArrayList<>();
  ArrayList<String> non_duplicate_label = new ArrayList<>() ;
  int last_count;
  LinkedHashMap<String, Integer> label_count_map = new LinkedHashMap<>();
  int label_count;
//  int counter = 0;
  String change = "false";
  String key;

  private static final Logger LOGGER = new Logger();

  // Configuration values for the prepackaged SSD model.
  private static final int TF_OD_API_INPUT_SIZE = 300;
  private static final boolean TF_OD_API_IS_QUANTIZED = true;
  private static final String TF_OD_API_MODEL_FILE = "detect.tflite";
  private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/labelmap.txt";
  private static final DetectorMode MODE = DetectorMode.TF_OD_API;
  // Minimum detection confidence to track a detection.
  private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;
  private static final boolean MAINTAIN_ASPECT = false;
  private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);
  private static final boolean SAVE_PREVIEW_BITMAP = false;
  private static final float TEXT_SIZE_DIP = 10;
  OverlayView trackingOverlay;
  private Integer sensorOrientation;

  private Classifier detector;

  private long lastProcessingTimeMs;
  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;
  private Bitmap cropCopyBitmap = null;

  private boolean computingDetection = false;

  private long timestamp = 0;

  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker;

  private BorderedText borderedText;

  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {
    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(this);

    int cropSize = TF_OD_API_INPUT_SIZE;

    try {
      detector =
          TFLiteObjectDetectionAPIModel.create(
              getAssets(),
              TF_OD_API_MODEL_FILE,
              TF_OD_API_LABELS_FILE,
              TF_OD_API_INPUT_SIZE,
              TF_OD_API_IS_QUANTIZED);
      cropSize = TF_OD_API_INPUT_SIZE;
    } catch (final IOException e) {
      e.printStackTrace();
      LOGGER.e(e, "Exception initializing classifier!");
      Toast toast =
          Toast.makeText(
              getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
      toast.show();
      finish();
    }

    previewWidth = size.getWidth();
    previewHeight = size.getHeight();

    sensorOrientation = rotation - getScreenOrientation();
    LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
    croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

    frameToCropTransform =
        ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            cropSize, cropSize,
            sensorOrientation, MAINTAIN_ASPECT);

    cropToFrameTransform = new Matrix();
    frameToCropTransform.invert(cropToFrameTransform);

    trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
    trackingOverlay.addCallback(
        new DrawCallback() {
          @Override
          public void drawCallback(final Canvas canvas) {
            tracker.draw(canvas);
            if (isDebug()) {
              tracker.drawDebug(canvas);
            }
          }
        });

    tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
  }

  @Override
  protected void processImage() {
    ++timestamp;
    final long currTimestamp = timestamp;
    trackingOverlay.postInvalidate();

    // No mutex needed as this method is not reentrant.
    if (computingDetection) {
      readyForNextImage();
      return;
    }
    computingDetection = true;
    LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

    rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

    readyForNextImage();

    final Canvas canvas = new Canvas(croppedBitmap);
    canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
    // For examining the actual TF input.
    if (SAVE_PREVIEW_BITMAP) {
      ImageUtils.saveBitmap(croppedBitmap);
    }

    runInBackground(
        new Runnable() {
          @Override
          public void run() {
            LOGGER.i("Running detection on image " + currTimestamp);
            final long startTime = SystemClock.uptimeMillis();
            final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
            final Canvas canvas = new Canvas(cropCopyBitmap);
            final Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setStyle(Style.STROKE);
            paint.setStrokeWidth(2.0f);

            float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
            switch (MODE) {
              case TF_OD_API:
                minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                break;
            }
//            System.out.println("Before");
//            System.out.println(results);
//            System.out.println("After");

            final List<Classifier.Recognition> mappedRecognitions =
                new LinkedList<Classifier.Recognition>();

            for (final Classifier.Recognition result : results) {
              final RectF location = result.getLocation();
              if (location != null && result.getConfidence() >= minimumConfidence) {
                canvas.drawRect(location, paint);

                cropToFrameTransform.mapRect(location);

                result.setLocation(location);
                mappedRecognitions.add(result);
//                System.out.println(Arrays.asList(result).indexOf(2));
//                System.out.println(result);

                String[] labelAndBoundingBoxes = String.valueOf(result).split(" ");

                labels.add(labelAndBoundingBoxes[1]);



              }
            }

            label_index.clear();
            System.out.println("testing1");
            for (String label : labels){

              System.out.println("testing2");
              if (!(non_duplicate_label.contains(label))){
                non_duplicate_label.add(label);
                label_count = search(labels, label);

              }
              System.out.println("testing3");
              System.out.println(label_count);
//              multiple_labels.removeAll(Collections.singleton(new String(label)));

              for (int num = 1; num <= label_count; num++ ){

                if ((label_count_map.containsKey(label+"_"+Integer.toString(num))) && (label_count_map.get(label+"_"+Integer.toString(num)) >= 0)) {
                  last_count = label_count_map.get(label+"_"+Integer.toString(num));
                  label_count_map.put(label+"_"+Integer.toString(num), last_count + 1);

                }
                else{
                  System.out.println("testing4");
                  label_count_map.put(label+"_"+Integer.toString(num), 1);
                  System.out.println(label_count_map);
                }
//                else if ((label_count_map.containsKey(label+"_"+Integer.toString(num))) && (label_count_map.get(label+"_"+Integer.toString(num)) == 0)){
//                  System.out.println("testing4");
//                  label_count_map.put(label+"_"+Integer.toString(num), 1);
//                  System.out.println(label_count_map);
//                }else if (!(label_count_map.containsKey(label+"_"+Integer.toString(num)))){
//                  label_count_map.put(label+"_"+Integer.toString(num), 1);
//                  System.out.println(label_count_map);
//                }

                int pos = new ArrayList<String>(label_count_map.keySet()).indexOf(label+"_"+Integer.toString(num));
                label_index.add(pos);
                System.out.println("label_index");
                System.out.println(label_index);
              }
            }
            labels_array_to_speak = printMap(label_count_map);
            System.out.println("label_count_map");
            System.out.println(label_count_map);
            ArrayList<String> keys = new ArrayList(label_count_map.keySet());
            if (change.equals("true")){
              tmp_object_map.clear();
              System.out.println("label_index");
              System.out.println(label_index);
              for (int index : label_index){
                System.out.println("index");
                System.out.println(index);
                System.out.println("keys");
                System.out.println(keys);

                System.out.println("labels_array_to_speak");
                System.out.println(labels_array_to_speak);

                key = keys.get(index);
                Integer value = label_count_map.get(key);
                tmp_object_map.put(key,value);
              }
              label_count_map.clear();
              label_count_map.putAll(tmp_object_map);
            }
            change = "true";

//public static void
                textToSpeech = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
                  @Override
                  public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                      int ttsLang = textToSpeech.setLanguage(Locale.US);
//                      String joined2 = String.join(",", array);
//                      String labels_speak = "";
//
//                      for (String s : labels_array_to_speak)
//                      {
//                        labels_speak += s + "a";
//                      }
                      for (String labels_speak : labels_array_to_speak){
                      String data = "There is a "+labels_speak+"in front of you";
                        tts(data);
                        if (ttsLang == TextToSpeech.LANG_MISSING_DATA
                                || ttsLang == TextToSpeech.LANG_NOT_SUPPORTED) {
                          Log.e("TTS", "The Language is not supported!");
                        } else {
                          Log.i("TTS", "Language Supported.");
                        }
                        Log.i("TTS", "Initialization success.");
                      }
                    } else {
                      Toast.makeText(getApplicationContext(), "TTS Initialization failed!", Toast.LENGTH_SHORT).show();
                    }
                  }
                });

//            }
            labels.clear();
            tracker.trackResults(mappedRecognitions, currTimestamp);
            trackingOverlay.postInvalidate();

            computingDetection = false;

            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    showFrameInfo(previewWidth + "x" + previewHeight);
                    showCropInfo(cropCopyBitmap.getWidth() + "x" + cropCopyBitmap.getHeight());
                    showInference(lastProcessingTimeMs + "ms");
                  }
                });
          }
        });
  }

  public void tts(String label){

    //                String data = editText.getText().toString();
    //                Log.i("TTS", "button clicked: " + data);
    int speechStatus = textToSpeech.speak(label, TextToSpeech.QUEUE_FLUSH, null);

    if (speechStatus == TextToSpeech.ERROR) {
      Log.e("TTS", "Error in converting Text to Speech!");
    }
  }


  public ArrayList<String> printMap(LinkedHashMap mp) {
    ArrayList<String> labels_to_speak = new ArrayList<>();
    ArrayList<String> keys_array = new ArrayList<>(mp.keySet());
    String speak_label;

    for (int i = 0; i < keys_array.size(); i++) {
      int iend = keys_array.get(i).indexOf("_");

      if ((int) mp.get(keys_array.get(i)) >= 10 ){

        if (iend != -1) {
          speak_label= (keys_array.get(i)).substring(0 , iend);
        }else{
          speak_label= keys_array.get(i);
        }

        labels_to_speak.add(speak_label);
        System.out.println("inside_print_map");
        System.out.println(mp.get(keys_array.get(i)));
        label_count_map.put(keys_array.get(i), 0);
//        label_count_map.remove(keys_array.get(i));
      }
    }
    return labels_to_speak;
  }

  static int search(ArrayList<String> labels, String label)
  {
    int label_counter = 0;
    for (int j = 0; j < labels.size(); j++)
      if (label.equals(labels.get(j)))
        label_counter++;

    return label_counter;
  }
//  public static void writeJson(String name) throws Exception {
//    JSONObject sampleObject = new JSONObject();
//    int count = 0;
//    if (sampleObject.has(name)){
//      count = (int)sampleObject.get(name) + 1;
//      sampleObject.put(name, count);
//    }else{
//      count = 0;
//      sampleObject.put(name, count);
//    }

//    sampleObject.put("age", 35);

//    JSONArray messages = new JSONArray();
//    messages.add("Hey!");
//    messages.add("What's up?!");

//    sampleObject.put("messages", messages);
//    Files.write(Paths.get(filename), sampleObject.toJSONString().getBytes());
//  }

  @Override
  protected int getLayoutId() {
    return R.layout.camera_connection_fragment_tracking;
  }

  @Override
  protected Size getDesiredPreviewFrameSize() {
    return DESIRED_PREVIEW_SIZE;
  }

  // Which detection model to use: by default uses Tensorflow Object Detection API frozen
  // checkpoints.
  private enum DetectorMode {
    TF_OD_API;
  }

  @Override
  protected void setUseNNAPI(final boolean isChecked) {
    runInBackground(() -> detector.setUseNNAPI(isChecked));
  }

  @Override
  protected void setNumThreads(final int numThreads) {
    runInBackground(() -> detector.setNumThreads(numThreads));
  }
}
