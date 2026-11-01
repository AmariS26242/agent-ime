package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.agent.AgentClient

/** First Agent surface: question, cancellable stream, and explicit host insertion. */
@Composable
fun AgentPanel(
    blocked: Boolean,
    onInsert: (String) -> Boolean,
    onBack: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    keyBgColor: Color,
    bottomPaddingDp: Dp,
    modifier: Modifier = Modifier,
) {
    val client = remember { AgentClient() }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (blocked) "敏感输入：已禁用 Agent" else "输入问题，结果可插入当前输入框") }
    var loading by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { client.cancel() } }

    Column(
        modifier = modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPaddingDp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Agent", color = textColor, style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { client.cancel(); onBack() }) { Text("返回") }
        }
        TextField(
            value = question,
            onValueChange = { question = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !blocked && !loading,
            label = { Text("问题") },
            singleLine = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !blocked && !loading && question.isNotBlank(),
                onClick = {
                    answer = ""
                    loading = true
                    status = "正在请求…"
                    client.ask(
                        question = question,
                        onDelta = { answer += it },
                        onDone = { loading = false; status = "已生成，可插入" },
                        onError = { loading = false; status = it },
                    )
                },
            ) { Text("发送") }
            if (loading) {
                OutlinedButton(onClick = { client.cancel(); loading = false; status = "已取消" }) { Text("取消") }
                CircularProgressIndicator(color = accentColor, modifier = Modifier.padding(10.dp).then(Modifier))
            }
        }
        Text(status, color = textColor.copy(alpha = 0.7f))
        Text(
            text = answer.ifBlank { if (blocked) "密码、验证码等敏感输入不会发送给 Agent。" else "回答会显示在这里。" },
            color = textColor,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Button(
            enabled = !blocked && !loading && answer.isNotBlank(),
            onClick = { if (onInsert(answer)) status = "已插入到输入框" else status = "当前输入域拒绝写入" },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("插入回答") }
    }
}
