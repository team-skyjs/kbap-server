package com.kbap.common.domain.notification

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.LanguageCode.EN
import com.kbap.common.domain.LanguageCode.ES
import com.kbap.common.domain.LanguageCode.ID
import com.kbap.common.domain.LanguageCode.JA
import com.kbap.common.domain.LanguageCode.KO
import com.kbap.common.domain.LanguageCode.RU
import com.kbap.common.domain.LanguageCode.TH
import com.kbap.common.domain.LanguageCode.VI
import com.kbap.common.domain.LanguageCode.ZH_HANS
import com.kbap.common.domain.LanguageCode.ZH_HANT
import com.kbap.common.domain.notification.model.NotificationType

object PushTemplates {
    val byType: Map<NotificationType, Map<LanguageCode, PushContent>> = mapOf(
        NotificationType.HELPFUL to mapOf(
            KO to PushContent("리뷰에 도움돼요를 받았어요", "{food} 리뷰가 다른 여행자에게 도움이 됐어요."),
            EN to PushContent("Someone found your review helpful", "Your review of {food} helped another traveler."),
            JA to PushContent("レビューに「役に立った」がつきました", "{food} のレビューが他の旅行者の役に立ちました。"),
            ZH_HANS to PushContent("你的评价获得了“有帮助”", "你对 {food} 的评价帮助了其他旅行者。"),
            ZH_HANT to PushContent("你的評價獲得了「有幫助」", "你對 {food} 的評價幫助了其他旅行者。"),
            VI to PushContent("Đánh giá của bạn được đánh dấu hữu ích", "Đánh giá về {food} của bạn đã giúp một du khách khác."),
            ID to PushContent("Ulasanmu dinilai membantu", "Ulasanmu tentang {food} membantu pelancong lain."),
            TH to PushContent("รีวิวของคุณได้รับ “มีประโยชน์”", "รีวิว {food} ของคุณช่วยนักท่องเที่ยวคนอื่นได้"),
            RU to PushContent("Ваш отзыв отметили как полезный", "Ваш отзыв о {food} помог другому путешественнику."),
            ES to PushContent("Tu reseña fue marcada como útil", "Tu reseña de {food} ayudó a otro viajero."),
        ),
        NotificationType.SCAN_SUGGESTION to mapOf(
            KO to PushContent("메뉴판을 찍어보세요", "오늘 먹을 메뉴, 스캔 한 번이면 알레르기까지 확인돼요."),
            EN to PushContent("Scan a menu today", "One scan shows what's in today's dish, allergens included."),
            JA to PushContent("メニューをスキャンしてみましょう", "今日の一皿、スキャン一回でアレルギーまで確認できます。"),
            ZH_HANS to PushContent("扫一扫菜单吧", "今天想吃什么？扫一下就能查看过敏原。"),
            ZH_HANT to PushContent("掃一掃菜單吧", "今天想吃什麼？掃一下就能查看過敏原。"),
            VI to PushContent("Quét thực đơn hôm nay", "Chỉ một lần quét để biết món hôm nay có gì, kể cả chất gây dị ứng."),
            ID to PushContent("Pindai menu hari ini", "Sekali pindai, alergen dalam hidangan hari ini langsung terlihat."),
            TH to PushContent("ลองสแกนเมนูวันนี้", "สแกนครั้งเดียว รู้ทั้งส่วนผสมและสารก่อภูมิแพ้ของเมนูวันนี้"),
            RU to PushContent("Отсканируйте меню", "Один скан покажет состав блюда и аллергены."),
            ES to PushContent("Escanea un menú hoy", "Con un escaneo verás qué lleva el plato de hoy, alérgenos incluidos."),
        ),
        NotificationType.REVIEW_REMINDER to mapOf(
            KO to PushContent("{food} 어땠나요?", "방금 드신 {food} 리뷰를 남겨주세요."),
            EN to PushContent("How was {food}?", "Leave a review of the {food} you just had."),
            JA to PushContent("{food} はいかがでしたか？", "さきほど召し上がった {food} のレビューを残してください。"),
            ZH_HANS to PushContent("{food} 怎么样？", "为你刚吃过的 {food} 写个评价吧。"),
            ZH_HANT to PushContent("{food} 怎麼樣？", "為你剛吃過的 {food} 寫個評價吧。"),
            VI to PushContent("{food} thế nào?", "Hãy để lại đánh giá cho món {food} bạn vừa ăn."),
            ID to PushContent("Bagaimana {food}-nya?", "Tulis ulasan untuk {food} yang baru saja kamu nikmati."),
            TH to PushContent("{food} เป็นอย่างไรบ้าง?", "ฝากรีวิว {food} ที่เพิ่งทานไปหน่อยนะ"),
            RU to PushContent("Как вам {food}?", "Оставьте отзыв о блюде {food}, которое вы только что попробовали."),
            ES to PushContent("¿Qué tal {food}?", "Deja una reseña del {food} que acabas de probar."),
        ),
        NotificationType.NOTICE to LanguageCode.entries.associateWith { PushContent("{title}", "{body}") },
        NotificationType.MEAL_TIME to mapOf(
            KO to PushContent("식사 시간이에요", "근처 한식, 지금 스캔으로 골라보세요."),
            EN to PushContent("It's mealtime", "Pick a Korean dish nearby with a quick scan."),
            JA to PushContent("食事の時間です", "近くの韓国料理、スキャンして選んでみましょう。"),
            ZH_HANS to PushContent("到饭点啦", "扫一扫，挑一道附近的韩餐吧。"),
            ZH_HANT to PushContent("到飯點啦", "掃一掃，挑一道附近的韓餐吧。"),
            VI to PushContent("Đến giờ ăn rồi", "Quét nhanh để chọn món Hàn gần bạn."),
            ID to PushContent("Waktunya makan", "Pindai cepat dan pilih hidangan Korea di dekatmu."),
            TH to PushContent("ถึงเวลาอาหารแล้ว", "สแกนเลือกอาหารเกาหลีใกล้คุณได้เลย"),
            RU to PushContent("Время поесть", "Отсканируйте меню и выберите корейское блюдо рядом."),
            ES to PushContent("Es hora de comer", "Escanea y elige un plato coreano cerca de ti."),
        ),
    )

    val optOutNotice: Map<LanguageCode, String> = mapOf(
        KO to "수신거부: 설정 > 알림",
        EN to "Unsubscribe: Settings > Notifications",
        JA to "配信停止: 設定 > 通知",
        ZH_HANS to "退订：设置 > 通知",
        ZH_HANT to "退訂：設定 > 通知",
        VI to "Hủy nhận: Cài đặt > Thông báo",
        ID to "Berhenti berlangganan: Pengaturan > Notifikasi",
        TH to "ยกเลิกรับข่าวสาร: การตั้งค่า > การแจ้งเตือน",
        RU to "Отписаться: Настройки > Уведомления",
        ES to "Cancelar suscripción: Ajustes > Notificaciones",
    )
}
