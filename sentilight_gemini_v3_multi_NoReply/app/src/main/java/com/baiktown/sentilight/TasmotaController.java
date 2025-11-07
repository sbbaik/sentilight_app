package com.baiktown.sentilight;

import com.baiktown.sentilight.BuildConfig;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.graphics.Color;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable; // 더 이상 사용되지 않지만 인터페이스 유지를 위해 남겨둠
import java.util.concurrent.ExecutionException; // 더 이상 사용되지 않음
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future; // 더 이상 사용되지 않음
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Android용 Tasmota + Gemini 제어 컨트롤러 (OkHttp 사용)
 * - Gemini: URL 쿼리 파라미터(?key=...) 방식
 * - Tasmota: HTTP GET /cm?cmnd=... (URL 인코딩 필수)
 */
public class TasmotaController {

    private static final String TAG = "TasmotaController";

    // ========================= 사용자 설정 (수정) =========================
    private volatile String apiKey;
    private volatile String geminiModel = "gemini-2.5-flash-lite";

    private volatile String tasmotaIpAddress = null;

    // 🌟 TasmotaIpManager 인스턴스
    private TasmotaIpManager tasmotaIpManager;
    // ====================================================================

    private static final int WAITING_TIME = 20; // 초 단위
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(WAITING_TIME, TimeUnit.SECONDS)
            .writeTimeout(WAITING_TIME, TimeUnit.SECONDS)
            .readTimeout(WAITING_TIME, TimeUnit.SECONDS)
            .callTimeout(WAITING_TIME * 2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** BuildConfig의 API 키 초기화를 위한 생성자 */
    public TasmotaController() {
        this.apiKey = safeString(BuildConfig.SENTILIGHT_LLM_API_KEY);
    }

    /** Tasmota 제어 결과 콜백 (메인스레드로 호출) */
    public interface ControllerCallback {
        void onSuccess(String command, String tasmotaResponse, String geminiExplanation, int colorRgb);
        void onFailure(String message);
    }

    /** 💡 [추가] Gemini 응답 파싱 직후 화면 업데이트용 콜백 (메인스레드로 호출) */
    public interface PreControlCallback {
        void onGeminiSuccess(String command, int colorRgb);
    }

    // -------------------- 외부 설정자 (Setter/Getter) --------------------

    public void setTasmotaIpAddress(String ipAddress) {
        // 🚨 참고: 이 메서드는 이제 사용하지 않도록 권장됩니다. IP는 Manager를 통해 관리되어야 합니다.
        this.tasmotaIpAddress = ipAddress;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = safeString(apiKey);
    }

    public void setGeminiModel(String model) {
        if (!isBlank(model)) this.geminiModel = model.trim();
    }

    // 💡 IP Manager 주입 메서드
    public void setIpManager(TasmotaIpManager ipManager) {
        this.tasmotaIpManager = ipManager;
        Log.i(TAG, "TasmotaIpManager 주입 완료. 현재 IP 개수: " + (ipManager != null ? ipManager.getIpCount() : "null"));
    }

    /** 💡 현재 제어할 IP 목록 반환 (TasmotaIpManager의 목록 사용을 강제) */
    public List<String> getIpList() {
        if (tasmotaIpManager != null && tasmotaIpManager.getIpCount() > 0) {
            List<String> ips = tasmotaIpManager.getAllIps();
            Log.d(TAG, "getIpList: Manager에서 " + ips.size() + "개의 IP 로드");
            return ips;
        }

        // 🚨 Manager가 없거나 비어있는 경우, 빈 목록을 반환하여 제어를 막습니다.
        Log.w(TAG, "getIpList: TasmotaIpManager에 등록된 IP가 없어 빈 목록을 반환합니다.");
        return new ArrayList<>();
    }

    // -------------------- 메인 진입점 --------------------
    /** 💡 [수정] 콜백을 ControllerCallback과 PreControlCallback 두 개를 받도록 수정 */
    public void processMoodAndControlLight(String moodText, ControllerCallback controlCallback, PreControlCallback screenCallback) {
        executor.execute(() -> {
            String fullGeminiResponse = null;
            String tasmotaCommand = null;
            String geminiExplanation = null;
            int finalColorRgb = 0;

            try {
                // 1. IP 목록 확인 (IP가 없어도 Gemini 호출은 시도함)
                List<String> ipsToControl = getIpList();

                // 2. Gemini 호출
                fullGeminiResponse = generateGeminiResponse(moodText);
                if (isBlank(fullGeminiResponse)) {
                    throw new IOException("Gemini가 빈 응답을 반환했습니다.");
                }

                // 3. [COMMAND:], [EXPLANATION:] 파싱
                tasmotaCommand = extractCommand(fullGeminiResponse);
                geminiExplanation = extractExplanation(fullGeminiResponse, tasmotaCommand);
                Log.d(TAG, "Gemini Command: " + tasmotaCommand);

                // 4. HSB 명령에서 정수형 RGB 값 추출
                finalColorRgb = convertHsbToRgb(tasmotaCommand);

                // -------------------------------------------------------------
                // 🌟 [핵심] Gemini 응답 파싱 직후 화면 업데이트 콜백 즉시 호출
                // -------------------------------------------------------------
                final String fCmd = tasmotaCommand;
                final int fRgb = finalColorRgb;
                mainHandler.post(() -> screenCallback.onGeminiSuccess(fCmd, fRgb));

                // 5. 실제 전송 (Tasmota 제어)
                String tasmotaResponse;
                if (ipsToControl.isEmpty()) {
                    tasmotaResponse = "ERROR: 등록된 Tasmota 전구 IP가 없어 제어 요청을 스킵했습니다.";
                } else {
                    // 🌟 [수정] Fire-and-Forget 방식으로 변경
                    sendToTasmotaAndForget(tasmotaCommand, ipsToControl);
                    tasmotaResponse = "OK: " + ipsToControl.size() + "대의 전구에 제어 명령을 비동기 전송했습니다."; // 응답을 무시하므로 가상의 성공 메시지 반환
                }

                final String fExp = geminiExplanation;
                final String fResp = tasmotaResponse;

                // 6. 최종 Tasmota 제어 결과 콜백 (메시지 전송이 성공했으므로 onSuccess를 호출)
                // 🚨 Note: 네트워크 오류 발생 여부와 관계없이 onSuccess가 호출됨
                mainHandler.post(() -> controlCallback.onSuccess(fCmd, fResp, fExp, fRgb));

            } catch (Exception e) {
                Log.e(TAG, "조명 제어 오류", e);
                final String fCmd = (tasmotaCommand != null) ? tasmotaCommand : "N/A";
                final String msg = "명령: " + fCmd + " / 오류: " + e.getMessage();

                // 오류 발생 시 최종 제어 콜백만 호출
                mainHandler.post(() -> controlCallback.onFailure(msg));
            }
        });
    }

    // -------------------- Gemini 호출부 (변경 없음) --------------------
    private String generateGeminiResponse(String userInput) throws IOException {
        final String key = this.apiKey;
        if (isBlank(key)) {
            throw new IOException("Gemini API 키가 설정되지 않았습니다. setApiKey(...) 또는 BuildConfig 값을 확인하세요.");
        }

        final String modelName = this.geminiModel.startsWith("models/")
                ? this.geminiModel
                : "models/" + this.geminiModel;

        final String base = "https://generativelanguage.googleapis.com/v1/" + modelName + ":generateContent";

        final String urlWithKey = base + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8.toString());

        String prompt =
                "사용자 기분: '" + userInput + "'. 이를 Tasmota 전구 제어 명령으로 변환하세요. " +
                        "결과 형식은 [COMMAND: HSBCOLOR hue,saturation,brightness;Dimmer value;CT temperature] " +
                        "이 세 가지 명령 조합으로만 출력하세요. " +
                        "[EXPLANATION: 기분 변화에 대한 설명] 으로만 출력하세요. " +
                        "(hue:0-359, saturation/brightness:0-100, Dimmer:0-100, CT:153-500). " +
                        "예: [COMMAND: HSBCOLOR 60,100,100;Dimmer 70;CT 250] [EXPLANATION: 밝고 따뜻한 노란색으로 활력을 줍니다.]";

        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);

        JsonArray contentsArray = new JsonArray();
        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        JsonArray partsArray = new JsonArray();
        partsArray.add(part);
        content.add("parts", partsArray);
        contentsArray.add(content);

        JsonObject requestBody = new JsonObject();
        requestBody.add("contents", contentsArray);

        RequestBody body = RequestBody.create(gson.toJson(requestBody), JSON);

        Request request = new Request.Builder()
                .url(urlWithKey)
                .post(body)
                .build();

        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (Response response = client.newCall(request).execute()) {
                String responseString = (response.body() != null) ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Gemini API 오류: HTTP " + response.code() + " / " + responseString);
                }

                JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);
                if (jsonResponse == null || !jsonResponse.has("candidates") || jsonResponse.getAsJsonArray("candidates").size() == 0) {
                    throw new IOException("Gemini 응답이 비어 있거나 후보가 없습니다.");
                }

                JsonObject contentObj = jsonResponse.getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonObject("content");
                if (contentObj == null || !contentObj.has("parts")) {
                    throw new IOException("Gemini 응답 파싱 실패(content/parts 없음).");
                }

                String generatedText = contentObj.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString();
                if (isBlank(generatedText)) {
                    throw new IOException("Gemini가 텍스트를 생성하지 못했습니다.");
                }
                return generatedText.trim();

            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException ignored) {
                }
            }
        }
        throw last != null ? last : new IOException("Gemini 호출 실패(원인 불명)");
    }

    // -------------------- HSB <-> RGB 변환 유틸리티 (변경 없음) --------------------
    /** HSB 값을 Android RGB 정수값으로 변환합니다. */
    private int convertHsbToRgb(String hsbCommand) {
        try {
            Pattern pattern = Pattern.compile("HSBCOLOR\\s*(\\d+),(\\d+),(\\d+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(hsbCommand);

            if (matcher.find()) {
                float h = Float.parseFloat(matcher.group(1)); // Hue (0-359)
                float s = Float.parseFloat(matcher.group(2)) / 100f; // Saturation (0.0 - 1.0)
                float v = Float.parseFloat(matcher.group(3)) / 100f; // Value/Brightness (0.0 - 1.0)

                return Color.HSVToColor(new float[]{h, s, v});
            }
        } catch (Exception e) {
            Log.e(TAG, "HSB to RGB conversion failed in command: " + hsbCommand, e);
        }
        // 변환 실패 시 기본값 (약간 어두운 파란색, #181B1C)
        return Color.parseColor("#181B1C");
    }


    // -------------------- 파싱기 (변경 없음) --------------------
    /** [COMMAND: ...] 블록에서 명령 추출 (허용 문자만 유지) */
    private String extractCommand(String fullResponse) {
        Pattern pattern = Pattern.compile("\\[COMMAND:\\s*(.*?)\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(fullResponse);
        if (matcher.find() && matcher.group(1) != null) {
            String raw = matcher.group(1).trim();
            String cleaned = raw.replaceAll("\\s+", " ");
            cleaned = cleaned.replaceAll("[^A-Za-z0-9,;\\s]", "");
            if (!cleaned.toUpperCase().contains("HSBCOLOR")) {
                return "HSBCOLOR 60,100,100;Dimmer 70;CT 250";
            }
            return cleaned;
        }
        return "HSBCOLOR 0,0,0;Dimmer 0;CT 500";
    }

    /** [EXPLANATION: ...] 블록에서 설명 추출 */
    private String extractExplanation(String fullResponse, String command) {
        Pattern pattern = Pattern.compile("\\[EXPLANATION:\\s*(.*?)\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(fullResponse);
        if (matcher.find() && matcher.group(1) != null) {
            return matcher.group(1).trim();
        }
        return command + " 명령을 생성했습니다. (설명 없음)";
    }

    // -------------------- Tasmota 전송부 (수정) --------------------

    /** 인코딩된 cmnd를 단일 IP로 GET 호출 (하위 호환성을 위해 유지되나 사용하지 않음) */
    private String sendToTasmotaRaw(String encodedCmnd, boolean throwOnNon200) throws IOException {
        // 🔴 위험 요소 수정 1: 단일 IP가 설정되어 있지 않으면 강제 실패
        if (isBlank(this.tasmotaIpAddress)) {
            throw new IOException("Tasmota 단일 IP 주소가 설정되지 않았습니다. TasmotaIpManager를 사용하세요.");
        }
        String url = "http://" + this.tasmotaIpAddress + "/cm?cmnd=" + encodedCmnd;
        return executeTasmotaRequest(url, throwOnNon200);
    }

    /**
     * 💡 [새로 추가된 메서드] 다중 IP에 Tasmota 명령을 전송하고 응답을 기다리지 않습니다.
     */
    private void sendToTasmotaAndForget(String rawCmnd, List<String> ipAddresses) {
        final String encodedCmnd = encodeCmndForUrl(rawCmnd);

        Log.i(TAG, "sendToTasmotaAndForget: 총 " + ipAddresses.size() + "개의 IP에 명령 비동기 전송.");

        for (String ip : ipAddresses) {
            // 각 IP별로 비동기 실행 (Fire-and-Forget)
            executor.execute(() -> {
                String url = "http://" + ip + "/cm?cmnd=" + encodedCmnd;
                try {
                    // 응답을 기다리지 않으므로, throwOnNon200은 false로 설정하고, 응답 코드를 무시합니다.
                    String response = executeTasmotaRequest(url, false);
                    Log.d(TAG, "IP " + ip + " 전송 완료 (응답 길이: " + response.length() + ")");
                } catch (IOException e) {
                    // 전송 실패는 로깅만 하고 앱의 메인 스레드에 영향을 주지 않습니다.
                    Log.e(TAG, "IP " + ip + " 제어 실패 (네트워크/연결 오류): " + e.getMessage());
                }
            });
        }
    }

    /** [제거됨] 기존의 sendToTasmotaRawMulti 메서드는 응답 대기 로직 때문에 제거되었습니다. */
    /*
    private String sendToTasmotaRawMulti(String rawCmnd, List<String> ipAddresses) throws ExecutionException, InterruptedException {
        // ... 기존 응답 대기 로직 ...
    }
    */

    /** HTTP 요청 실행을 위한 내부 공통 메서드 (변경 없음) */
    private String executeTasmotaRequest(String url, boolean throwOnNon200) throws IOException {
        Request req = new Request.Builder().url(url).get().build();

        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (Response resp = client.newCall(req).execute()) {
                String body = (resp.body() != null) ? resp.body().string() : "";
                if (!resp.isSuccessful() && throwOnNon200) {
                    throw new IOException("Tasmota 전송 실패: HTTP " + resp.code() + " / URL: " + url + " / " + body);
                }
                return body;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException ignored) {
                }
            }
        }
        throw last != null ? last : new IOException("Tasmota 호출 실패(원인 불명)");
    }


    /** 세미콜론 등 포함 명령을 URL-safe 하게 인코딩 (변경 없음) */
    private static String encodeCmndForUrl(String rawCmnd) {
        return URLEncoder.encode(rawCmnd, StandardCharsets.UTF_8);
    }

    // -------------------- 유틸: 프리셋 전송 (수정) --------------------
    public void sendPreset(String hsbc, int dimmer, int ct, ControllerCallback callback) {
        final String cmd = "HSBCOLOR " + hsbc + ";Dimmer " + dimmer + ";CT " + ct;

        executor.execute(() -> {
            int finalColorRgb = 0;

            try {
                // 색상 값 추출
                finalColorRgb = convertHsbToRgb(cmd);

                List<String> ipsToControl = getIpList();
                if (ipsToControl.isEmpty()) {
                    // 💡 IP 목록이 없으면 실패 메시지 전송
                    mainHandler.post(() -> callback.onFailure("Tasmota IP 주소가 설정되지 않았습니다. TasmotaIpManager에 등록해주세요."));
                    return;
                }

                // 💡 [수정] Fire-and-Forget 방식으로 변경
                sendToTasmotaAndForget(cmd, ipsToControl);
                String resp = "OK: " + ipsToControl.size() + "대의 전구에 제어 명령을 비동기 전송했습니다.";


                final String fResp = resp;
                final int fRgb = finalColorRgb;

                // 🚨 Note: Fire-and-Forget이므로 항상 onSuccess 호출
                mainHandler.post(() -> callback.onSuccess(cmd, fResp, "프리셋 적용", fRgb));
            } catch (Exception e) {
                final String msg = "명령: " + cmd + " / 오류: " + e.getMessage();
                mainHandler.post(() -> callback.onFailure(msg));
            }
        });
    }

    // -------------------- 내부 유틸 (변경 없음) --------------------
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safeString(String s) {
        return s == null ? "" : s;
    }
}