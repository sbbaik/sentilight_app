package com.baiktown.sentilight;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

// 💡 TasmotaIPManager 클래스가 별도로 정의되어 있다고 가정합니다.
// 💡 IpListAdapter 클래스가 별도로 정의되어 있다고 가정합니다.


public class IpManagerActivity extends AppCompatActivity
        implements IpListAdapter.OnIpActionListener { // 어댑터 리스너 구현

    // 🌟 FIX: TasmotaIpManager 인스턴스 변수명을 'tasmotaIpManager'로 통일
    private TasmotaIpManager tasmotaIpManager;
    private IpListAdapter ipListAdapter;
    private EditText editTextNewIp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // activity_ip_manager.xml 파일을 레이아웃으로 설정
        setContentView(R.layout.activity_ip_manager);

        // TasmotaIPManager 초기화 (데이터 관리자)
        // Context를 사용하여 SharedPreferences에 접근합니다.
        tasmotaIpManager = new TasmotaIpManager(this); // 🌟 FIX: 변수명 변경

        // UI 요소 초기화
        editTextNewIp = findViewById(R.id.editTextNewIp);
        Button buttonAddIp = findViewById(R.id.buttonAddIp);
        RecyclerView recyclerViewIpList = findViewById(R.id.recyclerViewIpList);

        // RecyclerView 설정
        recyclerViewIpList.setLayoutManager(new LinearLayoutManager(this));

        // 어댑터 초기화 및 연결
        ipListAdapter = new IpListAdapter(tasmotaIpManager.getIpList(), this); // 🌟 FIX: 변수명 변경
        recyclerViewIpList.setAdapter(ipListAdapter);

        // [추가] 버튼 리스너 설정
        buttonAddIp.setOnClickListener(v -> addIpAddress());

        // Activity 상단에 타이틀 설정 (선택 사항)
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Tasmota 전구 IP 관리");
        }
    }

    /** 새로운 IP 주소를 목록에 추가하는 로직 */
    private void addIpAddress() {
        String newIp = editTextNewIp.getText().toString().trim();

        if (TextUtils.isEmpty(newIp)) {
            Toast.makeText(this, "IP 주소를 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean success = tasmotaIpManager.addIpAddress(newIp); // 🌟 FIX: 변수명 변경

        if (success) {
            Toast.makeText(this, newIp + " 추가 완료", Toast.LENGTH_SHORT).show();
            editTextNewIp.setText(""); // 입력 필드 초기화
            refreshIpList(); // RecyclerView 업데이트
        } else {
            Toast.makeText(this, "IP 주소 형식이 올바르지 않거나 이미 존재합니다.", Toast.LENGTH_LONG).show();
        }
    }

    /** IP 목록을 새로고침하고 RecyclerView에 반영합니다. */
    private void refreshIpList() {
        // TasmotaIpManager에서 최신 목록을 가져와 어댑터를 업데이트합니다.
        ipListAdapter.updateList(tasmotaIpManager.getIpList()); // 🌟 FIX: 변수명 변경
    }

    // -------------------- OnIpActionListener 구현 (삭제 이벤트) --------------------

    /** IpListAdapter에서 [삭제] 버튼 클릭 시 호출됩니다. */
    @Override
    public void onDeleteClick(String ipAddress) {
        boolean success = tasmotaIpManager.removeIpAddress(ipAddress); // 🌟 FIX: 변수명 변경

        if (success) {
            Toast.makeText(this, ipAddress + " 삭제 완료", Toast.LENGTH_SHORT).show();
            refreshIpList(); // RecyclerView 업데이트
        } else {
            Toast.makeText(this, "삭제 실패", Toast.LENGTH_SHORT).show();
        }
    }
}