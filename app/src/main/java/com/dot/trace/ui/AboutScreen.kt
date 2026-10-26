package com.dot.trace.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.dot.trace.ui.theme.Dot
import java.security.MessageDigest

private const val AUTHOR = "curlyahn"
private const val REPO_URL = "https://github.com/curlyahn/dotTrace"

private data class HelpItem(val title: String, val body: String)

private val helpItems = listOf(
    HelpItem(
        "기록 시작 · 정지",
        "지도 아래 카드의 '기록 시작'을 누르면 위치 기록이 시작돼요. 기록하는 동안 알림창에 'DotTrace'가 계속 떠 있어요.\n" +
            "알림의 '기록 정지' 또는 카드의 '기록 정지'로 멈출 수 있어요.\n" +
            "처음에는 위치(정확한 위치)·알림·신체 활동 권한을 허용해 주세요.",
    ),
    HelpItem(
        "얼마나 자주 기록하나요?",
        "• 움직이는 동안: 설정한 주기(기본 1분)마다\n" +
            "• 빠르게 이동(시속 30km 이상)하거나 차·자전거에 타면: 15초마다 — 경로가 부드럽게 그려져요\n" +
            "• 가만히 있으면: 위치 업데이트를 꺼서 배터리를 아끼고, 다시 움직이면 바로 이어서 기록해요\n" +
            "• 터널·지하처럼 신호가 없으면: 처음 3분은 10초마다, 오래 이어지면 최대 15분 간격으로 다시 찾아요",
    ),
    HelpItem(
        "지도 보는 법",
        "날짜 막대의 < >로 다른 날을, 날짜를 누르면 달력에서 고를 수 있어요.\n" +
            "경로는 이동 수단별 색으로 칠해져요: 걷기(회색) · 자전거(라임) · 버스(파랑) · 지하철(보라) · 자가용(주황) · 기차(분홍).\n" +
            "점을 누르면 그때의 시각·주소·정확도·속도가 보여요. 오른쪽 아래 버튼은 지금 위치를 파란 점으로 보여 줘요.",
    ),
    HelpItem(
        "하루 타임라인 · 고치기",
        "카드의 '타임라인'을 누르면 그날 머문 곳과 이동이 시간순으로 나와요. 항목을 누르면 지도에서 보여 줘요.\n" +
            "연필을 누르면 머문 곳 이름이나 이동 수단을 고칠 수 있어요. '다음에도'를 켜 두면 같은 장소·같은 출발지와 도착지 이동에 자동으로 적용돼요. " +
            "기억한 내용은 설정 → 내 장소 아래에서 지울 수 있어요.\n" +
            "지도에서 머문 곳은 원 하나로 보이고, 이름과 머문 시간이 함께 나와요. 이름은 직접 고친 이름 → 내 장소 → 지도에 나온 상호 → 주소 순서로 붙어요.",
    ),
    HelpItem(
        "집 · 회사 자동 제안",
        "최근 3주 동안 밤을 자주 보낸 곳, 평일 낮에 오래 머문 곳이 있으면 지도 위에 '집(회사)으로 등록할까요?'가 떠요. '아니요'를 누르면 같은 곳은 다시 묻지 않아요.",
    ),
    HelpItem(
        "지점 목록 · 이동 목록",
        "카드의 'N 지점 >'을 누르면 그날 기록한 곳이 시각·주소와 함께 목록으로 나와요. 같은 곳에 이어 찍힌 지점은 한 줄로 묶여요.\n" +
            "카드 아래 칩(예: 지하철 · 12.3km)을 누르면 그 이동만 지도에서 강조돼요.",
    ),
    HelpItem(
        "이동 수단은 어떻게 알아내나요?",
        "휴대폰의 활동 인식(걷기·자전거·차량), 속도, 위치 신호가 끊긴 채 이동한 구간(지하철), " +
            "함께 이동하는 와이파이(버스·지하철), 정차 패턴(버스) 등을 종합해서 추정해요. " +
            "추정이라 틀릴 수 있어요. 특히 시내 자가용과 버스는 헷갈리기 쉬워요.",
    ),
    HelpItem(
        "내 장소 (집 · 회사 · 자주 가는 곳)",
        "설정 → 내 장소에서 집·회사와 자주 가는 곳을 최대 10개까지 등록할 수 있어요. 위쪽 검색창에 주소나 건물 이름을 입력하거나, 지도를 움직여 가운데 핀을 맞추고 저장하세요.\n" +
            "등록한 장소는 지도에 이름표로 보이고, 지점 목록과 리포트에서 주소 대신 이름으로 나와요.",
    ),
    HelpItem(
        "월간 · 연간 리포트",
        "날짜 옆 막대그래프 버튼을 누르면 리포트를 볼 수 있어요. 수단별 이동 거리·시간, 가장 오래 머문 곳, 방문한 지역을 보여 줘요.\n" +
            "매월 1일에는 지난달, 매년 1월 1일에는 지난해 리포트가 준비됐다고 알려 드려요.",
    ),
    HelpItem(
        "구글 타임라인 가져오기",
        "1) 휴대폰 설정 → 위치 → 위치 서비스 → 타임라인 → '타임라인 데이터 내보내기'로 Timeline.json을 저장해요.\n" +
            "2) DotTrace 설정 → 구글 타임라인 → '구글 타임라인 가져오기'에서 그 파일을 골라요.\n" +
            "이동 경로·머문 곳·구글이 판단한 이동 수단(버스·지하철·기차 등)을 가져와요. 이미 있는 기록과 1분 안에 겹치면 건너뛰어서 여러 번 가져와도 중복되지 않아요. " +
            "예전 구글 테이크아웃의 Records.json도 가져올 수 있어요. 가져온 기록만 따로 지울 수도 있어요.",
    ),
    HelpItem(
        "개인정보",
        "모든 기록은 이 휴대폰 안에만 저장되고 어디로도 보내지 않아요. 휴대폰 자동 백업에서도 빠져 있어요.\n" +
            "지도는 Google 지도를 쓰고, 지도 그림만 받아와요. " +
            "머문 곳 이름 찾기를 켜 두면 이름 모르는 머문 곳의 좌표 하나만 Google 장소 정보에 물어보고, 받은 이름은 30일 뒤 지워요(설정에서 끌 수 있어요). " +
            "위치 계산과 주소 변환은 휴대폰(OS)의 기능을 써요.\n" +
            "백업이 필요하면 설정 → 내 기록에서 GPX 파일로 저장하세요.",
    ),
    HelpItem(
        "기록이 멈추거나 빠질 때",
        "• 설정 → 안정적으로 기록하기의 세 항목(신체 활동 감지, 위치 '항상 허용', 배터리 최적화 제외)을 모두 '완료'로 만들어 주세요.\n" +
            "• 삼성 휴대폰은 설정 → 배터리 → 백그라운드 사용 제한 → 절전 예외 앱에 DotTrace를 추가하세요.\n" +
            "• 실내·고층에서는 GPS가 약해 Wi-Fi로 위치를 잡아 수십 m 오차가 생길 수 있어요.",
    ),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val info = remember { appInfo(context) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Dot.Ink)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로", tint = Dot.Text) }
        }

        // 앱 소개
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(Dot.Lime))
            Spacer(Modifier.height(16.dp))
            Text("DotTrace", style = MaterialTheme.typography.displaySmall, color = Dot.Text)
            Text("나만 보는 위치 타임라인", style = MaterialTheme.typography.bodyMedium, color = Dot.Muted)
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Dot.Surface2)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("버전 ${info.versionName} · 빌드 ${info.versionCode}", style = MaterialTheme.typography.labelSmall, color = Dot.Lime)
            }
        }

        // 도움말
        SectionTitle("도움말")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Dot.Surface),
        ) {
            helpItems.forEachIndexed { i, item ->
                HelpRow(item)
                if (i < helpItems.lastIndex) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(Dot.Line))
                }
            }
        }

        // 만든 이
        SectionTitle("만든 이")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Dot.Surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(AUTHOR, style = MaterialTheme.typography.headlineSmall, color = Dot.Text)
            Text("Claude와 함께 만들었어요.", style = MaterialTheme.typography.bodyMedium, color = Dot.Muted)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Dot.Surface2)
                    .clickable { uri.openUri(REPO_URL) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("GitHub에서 보기", style = MaterialTheme.typography.labelLarge, color = Dot.Text)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = Dot.Muted, modifier = Modifier.size(16.dp))
            }
        }

        // 앱 정보 (문제 해결용)
        SectionTitle("앱 정보")
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Dot.Surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoLine("패키지", context.packageName)
                InfoLine("서명 SHA-1", info.sha1)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Dot.Muted,
        modifier = Modifier.padding(start = 24.dp, top = 28.dp, bottom = 10.dp),
    )
}

@Composable
private fun HelpRow(item: HelpItem) {
    var open by rememberSaveable(item.title) { mutableStateOf(false) }
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, color = Dot.Text, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null, tint = Dot.Muted, modifier = Modifier.rotate(rot))
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                item.body,
                style = MaterialTheme.typography.bodyMedium,
                color = Dot.Muted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Dot.Faint)
        Text(value, style = MaterialTheme.typography.labelLarge, color = Dot.Text)
    }
}

private data class AppInfo(val versionName: String, val versionCode: Long, val sha1: String)

@Suppress("DEPRECATION")
private fun appInfo(context: Context): AppInfo {
    val pm = context.packageManager
    val pkg = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
    val sha1 = runCatching {
        val cert = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo!!.apkContentsSigners[0]
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!![0]
        }
        MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("알 수 없음")
    return AppInfo(
        versionName = pkg?.versionName ?: "?",
        versionCode = pkg?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0,
        sha1 = sha1,
    )
}
