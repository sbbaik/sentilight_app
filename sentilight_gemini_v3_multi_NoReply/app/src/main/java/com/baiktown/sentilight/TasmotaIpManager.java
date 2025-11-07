package com.baiktown.sentilight;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log; // 💡 Log 추가
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tasmota 전구의 IP 주소 목록을 관리하고 SharedPreferences에 JSON으로 저장하는 클래스.
 */
public class TasmotaIpManager {
    private static final String TAG = "TasmotaIpManager";
    private static final String PREF_NAME = "TasmotaIPPrefs";
    private static final String KEY_IP_LIST = "ipList";

    // 💡 IPv4 유효성 검사를 위한 강화된 정규식 패턴 (0-255 범위 검증)
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
                    "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");

    // ⚠️ FIX 1: ipList 멤버 변수를 삭제합니다. (매번 SharedPreferences에서 읽어오도록 변경)
    // private final List<String> ipList = new ArrayList<>();
    private final Context context;
    private final SharedPreferences prefs;
    private final Gson gson = new Gson();
    private final Type listType = new TypeToken<ArrayList<String>>() {}.getType(); // Type을 멤버 변수로 선언하여 효율성 개선

    public TasmotaIpManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        // 🌟 FIX: 초기 IP 목록이 비어 있으면 기본 IP를 설정합니다.
        List<String> currentIps = loadIpsFromPrefs();
        if (currentIps.isEmpty()) {
            List<String> defaultIps = new ArrayList<>();
            defaultIps.add("192.168.0.50");
            defaultIps.add("192.168.0.51");
            defaultIps.add("192.168.0.52");
            defaultIps.add("192.168.0.53");
            defaultIps.add("192.168.0.54");
            saveIpList(defaultIps);
            Log.i(TAG, "Default IPs set and saved.");
        }
    }

    /** SharedPreferences에서 IP 목록을 불러오는 내부 함수입니다. (캐시 대신 항상 저장소에서 로드) */
    private List<String> loadIpsFromPrefs() {
        String json = prefs.getString(KEY_IP_LIST, null);
        if (json != null) {
            List<String> loadedList = gson.fromJson(json, listType);
            return (loadedList != null) ? loadedList : new ArrayList<>();
        }
        return new ArrayList<>();
    }

    /** 주어진 IP 목록을 SharedPreferences에 저장합니다. */
    private void saveIpList(List<String> listToSave) {
        String json = gson.toJson(listToSave);
        prefs.edit().putString(KEY_IP_LIST, json).apply();
        Log.d(TAG, "IP list saved. Total: " + listToSave.size());
    }

    /** IPv4 유효성 검사 메서드 */
    private boolean isValidIpv4(String ip) {
        if (ip == null) {
            return false;
        }
        Matcher matcher = IPV4_PATTERN.matcher(ip);
        return matcher.matches();
    }

    // -------------------- 공개 API (IP 관리) --------------------

    // 💡 FIX 2: TasmotaController가 이 메서드를 호출할 때마다 SharedPreferences에서 최신 목록을 로드합니다.
    /** * 현재 저장된 IP 주소 목록을 ArrayList<String> 형태로 반환합니다. */
    public ArrayList<String> getAllIps() {
        List<String> latestList = loadIpsFromPrefs();
        // 불변 리스트를 반환하여 외부에서 직접적인 수정을 방지
        return new ArrayList<>(Collections.unmodifiableList(latestList));
    }


    /** 현재 저장된 IP 주소 목록을 반환합니다. (기존 getIpList 유지) */
    public List<String> getIpList() {
        // 이 메서드도 항상 최신 데이터를 로드합니다.
        List<String> latestList = loadIpsFromPrefs();
        return Collections.unmodifiableList(latestList);
    }

    /** IP 주소를 목록에 추가하고 저장합니다. */
    public boolean addIpAddress(String ip) {
        String cleanIp = ip.trim();
        List<String> currentIps = loadIpsFromPrefs(); // 최신 목록 로드

        // 강화된 IP 유효성 검사 적용 및 중복 확인
        if (!cleanIp.isEmpty() && !currentIps.contains(cleanIp) && isValidIpv4(cleanIp)) {
            currentIps.add(cleanIp);
            saveIpList(currentIps); // 추가 후 저장
            return true;
        }
        return false;
    }

    // 💡 FIX 3: IP 삭제 시, SharedPreferences에 저장된 목록을 불러와서 처리 후 저장합니다.
    /** 특정 IP 주소를 목록에서 삭제하고 저장합니다. */
    public boolean removeIpAddress(String ip) {
        String cleanIp = ip.trim();
        List<String> currentIps = loadIpsFromPrefs(); // 최신 목록 로드

        if (currentIps.remove(cleanIp)) {
            saveIpList(currentIps); // 삭제 후 저장
            return true;
        }
        return false;
    }

    /** 목록 전체를 설정하고 저장합니다. */
    public void setAllIpAddresses(List<String> newIpList) {
        List<String> listToSave = (newIpList != null) ? newIpList : new ArrayList<>();
        saveIpList(listToSave);
    }

    /** 저장된 IP 주소의 개수를 반환합니다. */
    public int getIpCount() {
        // 💡 FIX 4: 개수 반환 시에도 항상 최신 목록을 로드합니다.
        return loadIpsFromPrefs().size();
    }
}