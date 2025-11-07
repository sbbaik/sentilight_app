package com.baiktown.sentilight;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.widget.FrameLayout;
import android.graphics.drawable.GradientDrawable;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.model.KeyPath;
import com.airbnb.lottie.value.LottieValueCallback;
import com.airbnb.lottie.LottieProperty;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements RecognitionListener {

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;

    // 애니메이션 상수
    private static final float SCALE_UP_FACTOR = 1.05f;
    private static final long ANIMATION_DURATION = 100;

    // UI 요소
    private TextView resultTextView;
    private EditText ipInputView;
    private View rootView;

    private View settingsIcon; // IP 관리 화면으로 이동하는 아이콘

    private ImageView backgroundIconView; // 사용자가 터치할 전구 이미지
    private LottieAnimationView lottieAnimationView; // 애니메이션 효과를 보여줄 Lottie 뷰

    private FrameLayout lightContainer;

    // 배경색
    private static final int INITIAL_BACKGROUND_COLOR = Color.parseColor("#4285F4"); // 메인 화면 기본 파란색

    // 음성 인식 요소
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private boolean isListening = false;

    // Tasmota 제어 요소
    private TasmotaController tasmotaController;

    // Tasmota IP Manager
    private TasmotaIpManager ipManager;

    private String[] permissions = {Manifest.permission.RECORD_AUDIO};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 뷰 초기화
        rootView = findViewById(android.R.id.content).getRootView();
        rootView.setBackgroundColor(INITIAL_BACKGROUND_COLOR);
        resultTextView = findViewById(R.id.resultTextView);

        ipInputView = findViewById(R.id.ipInputView); // IP 상태 표시용 뷰
        backgroundIconView = findViewById(R.id.backgroundIconView);
        lottieAnimationView = findViewById(R.id.animatedIconView);
        lightContainer = findViewById(R.id.lightContainer);
        settingsIcon = findViewById(R.id.settings_icon);

        // ----------------------------------------------------------------------
        // 🌟 TasmotaIPManager 및 TasmotaController 연결 로직 🌟
        // ----------------------------------------------------------------------
        ipManager = new TasmotaIpManager(getApplicationContext());
        tasmotaController = new TasmotaController();
        tasmotaController.setIpManager(ipManager);

        // 💡 초기 IP 상태 표시
        updateIpStatusView();

        // ----------------------------------------------------------------------

        // 권한 요청
        requestAudioPermission();

        // SpeechRecognizer 설정
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(this);
        } else {
            Toast.makeText(this, "음성 인식 서비스가 지원되지 않습니다.", Toast.LENGTH_LONG).show();
            return;
        }

        // 음성 인식 Intent 설정
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        // 클릭/터치 리스너 (기존 유지)
        backgroundIconView.setOnClickListener(v -> {
            if (isListening) {
                stopListening();
            } else {
                startListening();
            }
        });

        backgroundIconView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                animateScale(v, SCALE_UP_FACTOR, ANIMATION_DURATION);
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                animateScale(v, 1.0f, ANIMATION_DURATION);
            }
            return false; // 클릭 이벤트가 호출되도록 false 반환
        });

        // 설정 아이콘 리스너
        settingsIcon.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, IpManagerActivity.class);
            startActivity(intent);
        });
    }

    // -------------------------------------------------------------
    // 💡 onResume에서 IP 목록 갱신 및 UI 업데이트
    // -------------------------------------------------------------
    @Override
    protected void onResume() {
        super.onResume();
        updateIpStatusView();
    }

    /** * IP 입력/상태 뷰를 최신 IP 목록 상태에 맞춰 갱신합니다.
     */
    private void updateIpStatusView() {
        if (ipManager != null) {
            int ipCount = ipManager.getIpCount();
            if (ipCount > 0) {
                String firstIp = ipManager.getAllIps().get(0);
                String statusText = firstIp + (ipCount > 1 ? " 외 " + (ipCount - 1) + "개" : "");
                ipInputView.setText(statusText);
            } else {
                ipInputView.setText("IP 미등록 (관리 필요)");
            }
            // IP 입력 뷰는 사용자가 직접 수정하지 못하도록 비활성화
            ipInputView.setEnabled(false);
        }
    }

    // -------------------------------------------------------------
    // 🌟 색상 처리 로직 유지
    // -------------------------------------------------------------

    /**
     * 배경색(color)과 대비되면서도 세련된(고채도 또는 고명도) Lottie 색상 필터 색상을 계산합니다.
     * @param color 배경색 (int RGB)
     * @return 대비되는 필터 색상 (int RGB)
     */
    private int getContrastingColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);

        // 1. Hue (색조)를 보색(180도 회전)으로 설정합니다.
        float complementaryHue = (hsv[0] + 180) % 360;

        // 2. Saturation (채도) 조절: 배경색의 채도와 상관없이 Lottie 색상의 채도를 높여 시인성을 확보합니다.
        float contrastingSaturation = hsv[1] < 0.5f ? 0.95f : 0.75f;

        // 3. Value (명도) 조절: 어두운 배경색에서 Lottie를 밝게, 밝은 배경색에서 Lottie를 어둡게 만듭니다.
        float contrastingValue = hsv[2] < 0.5f ? 0.95f : 0.25f;

        // 최종 HSV 배열 생성 및 ARGB로 변환
        return Color.HSVToColor(Color.alpha(color), new float[]{complementaryHue, contrastingSaturation, contrastingValue});
    }

    // 💡 Lottie 색상 필터를 적용/제거하는 함수 수정
    private void setLottieColorFilter(int color) {
        ColorFilter filter = color == INITIAL_BACKGROUND_COLOR
                ? null // 초기화 시 null을 전달하여 필터 제거
                : new PorterDuffColorFilter(getContrastingColor(color), PorterDuff.Mode.SRC_ATOP); // 🌟 getContrastingColor 사용

        LottieValueCallback<ColorFilter> colorFilterCallback = new LottieValueCallback<>(filter);

        // 모든 Lottie 요소에 필터 적용
        lottieAnimationView.addValueCallback(
                new KeyPath("**"),
                LottieProperty.COLOR_FILTER,
                colorFilterCallback
        );
    }

    /**
     * lightContainer 배경색을 안전하게 변경하는 함수 유지
     */
    private void setLightContainerColor(int colorRgb) {
        if (lightContainer == null) return;

        // 둥근 사각형 Drawable(GradientDrawable)을 유지하면서 색상만 변경
        if (lightContainer.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) lightContainer.getBackground().mutate()).setColor(colorRgb);
        } else {
            // Drawable이 없는 경우를 대비
            lightContainer.setBackgroundColor(colorRgb);
        }
    }


    private void animateScale(View view, float scale, long duration) {
        view.animate().scaleX(scale).scaleY(scale).setDuration(duration).start();
    }

    // ------------------- 권한 및 음성 인식 로직 유지 -------------------

    private void requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION);
        }
    }

    private void startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            speechRecognizer.startListening(recognizerIntent);
            isListening = true;
            resultTextView.setText("말씀해주세요...");
            lottieAnimationView.setVisibility(View.VISIBLE);
            lottieAnimationView.playAnimation();
        } else {
            Toast.makeText(this, "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
            requestAudioPermission();
        }
    }

    private void stopListening() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        isListening = false;
        lottieAnimationView.cancelAnimation();
        lottieAnimationView.setVisibility(View.INVISIBLE);

        // 음성 인식이 중단되면 Lottie 색상 필터 초기화
        setLottieColorFilter(INITIAL_BACKGROUND_COLOR);
    }

    // ------------------- RecognitionListener 콜백 메소드 -------------------

    @Override
    public void onReadyForSpeech(Bundle params) {
        resultTextView.setText("음성 인식 준비 완료. 말하세요...");
        lottieAnimationView.setVisibility(View.VISIBLE);
        lottieAnimationView.playAnimation();
    }

    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { resultTextView.setText("처리 중..."); }

    @Override
    public void onError(int error) {
        lottieAnimationView.cancelAnimation();
        lottieAnimationView.setVisibility(View.INVISIBLE);
        isListening = false;

        // lightContainer와 Lottie 색상 필터 초기화
        setLightContainerColor(INITIAL_BACKGROUND_COLOR);
        setLottieColorFilter(INITIAL_BACKGROUND_COLOR);

        String message;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH: message = "일치하는 결과를 찾을 수 없습니다."; break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: message = "말씀이 없어 시간 초과되었습니다."; break;
            case SpeechRecognizer.ERROR_SERVER: message = "서버 오류 (오프라인 팩 확인 필요)"; break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: message = "인식 서비스 사용 중입니다."; break;
            case SpeechRecognizer.ERROR_AUDIO: message = "오디오 녹음 오류 (마이크 권한 재확인)"; break;
            default: message = "알 수 없는 오류 발생: " + error; break;
        }
        resultTextView.setText("오류: " + message);
        Toast.makeText(this, "음성 인식 오류: " + message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onResults(Bundle results) {
        isListening = false;

        ArrayList<String> data = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (data != null && !data.isEmpty()) {
            final String recognizedText = data.get(0);
            resultTextView.setText("인식: " + recognizedText + "\n조명 명령 생성 및 처리 중...");

            // 💡 [수정] TasmotaController.processMoodAndControlLight 호출 시 PreControlCallback 추가
            tasmotaController.processMoodAndControlLight(recognizedText,
                    // 1. ControllerCallback (Tasmota 제어 결과)
                    new TasmotaController.ControllerCallback() {
                        @Override
                        public void onSuccess(String command, String tasmotaResponse, String geminiExplanation, int colorRgb) {
                            // Tasmota 제어 성공 시, 화면 UI 갱신 (애니메이션 중단 및 텍스트)만 수행
                            lottieAnimationView.cancelAnimation();
                            lottieAnimationView.setVisibility(View.INVISIBLE);

                            resultTextView.setText(
                                    "인식: " + recognizedText + "\n" +
                                            "COMMAND: " + command + "\n" +
                                            "전구 응답: " + tasmotaResponse
                            );
                            Toast.makeText(MainActivity.this, "조명 제어 완료!", Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onFailure(String message) {
                            // Tasmota 제어 실패 시, UI 초기화 및 실패 메시지 표시
                            lottieAnimationView.cancelAnimation();
                            lottieAnimationView.setVisibility(View.INVISIBLE);

                            // 🚨 제어 실패 시, lightContainer와 Lottie 색상 필터 초기화
                            setLightContainerColor(INITIAL_BACKGROUND_COLOR);
                            setLottieColorFilter(INITIAL_BACKGROUND_COLOR);

                            resultTextView.setText("인식: " + recognizedText + "\n실패: " + message);
                            Toast.makeText(MainActivity.this, "조명 제어 실패", Toast.LENGTH_LONG).show();
                        }
                    },
                    // 2. PreControlCallback (Gemini 응답 직후 화면 업데이트)
                    new TasmotaController.PreControlCallback() {
                        @Override
                        public void onGeminiSuccess(String command, int colorRgb) {
                            // 🌟 [핵심 로직] Tasmota 제어 전, Gemini 응답 파싱 직후 화면 색상 즉시 변경
                            setLightContainerColor(colorRgb);
                            setLottieColorFilter(colorRgb);
                            resultTextView.append("\n(색상 명령 수신 완료)");
                        }
                    }
            );
        } else {
            resultTextView.setText("결과 없음");
            Toast.makeText(this, "음성 인식 결과가 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onPartialResults(Bundle partialResults) { /* 기존 코드와 동일 */ }
    @Override public void onEvent(int eventType, Bundle params) { /* 기존 코드와 동일 */ }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "마이크 권한 승인됨.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "마이크 권한이 거부되었습니다. 음성 인식을 사용할 수 없습니다.", Toast.LENGTH_LONG).show();
            }
        }
    }
}