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
import com.kbap.common.domain.notification.model.MealSlot
import com.kbap.common.domain.notification.model.NotificationType

object PushTemplates {
    val byType: Map<NotificationType, Map<LanguageCode, PushContent>> = mapOf(
        NotificationType.HELPFUL to mapOf(
            KO to PushContent("리뷰가 도움이 됐어요", "{food} 리뷰에 누군가 ‘도움돼요’를 눌렀어요."),
            EN to PushContent("Your review made a difference", "Someone marked your {food} review as helpful."),
            JA to PushContent("レビューが役立っています", "{food}のレビューに「役に立った」が届きました。"),
            ZH_HANS to PushContent("你的评价帮到别人了", "有人为你的{food}评价点了“有帮助”。"),
            ZH_HANT to PushContent("你的評論幫到別人了", "有人為你的{food}評論點了「有幫助」。"),
            VI to PushContent("Đánh giá đã giúp ích", "Có người thấy đánh giá {food} của bạn hữu ích."),
            ID to PushContent("Ulasanmu bermanfaat", "Ada yang merasa terbantu oleh ulasanmu tentang {food}."),
            TH to PushContent("รีวิวของคุณช่วยคนอื่นได้", "มีคนกด “มีประโยชน์” ให้รีวิว {food} ของคุณ"),
            RU to PushContent("Ваш отзыв пригодился", "Кто-то отметил ваш отзыв о {food} как полезный."),
            ES to PushContent("Tu reseña ha sido útil", "Alguien marcó tu reseña de {food} como útil."),
        ),
        NotificationType.SCAN_SUGGESTION to mapOf(
            KO to PushContent("낯선 메뉴가 있나요", "메뉴판을 스캔하면 어떤 음식인지 살펴볼 수 있어요."),
            EN to PushContent("Curious about a dish?", "Try scanning the menu to learn about the dishes."),
            JA to PushContent("知らない料理が気になったら", "メニューをスキャンすると、どんな料理か調べられます。"),
            ZH_HANS to PushContent("遇到不熟悉的菜名？", "不妨扫一扫菜单，看看是什么菜。"),
            ZH_HANT to PushContent("遇到不熟悉的菜名？", "不妨掃描菜單，看看是什麼料理。"),
            VI to PushContent("Tò mò về món ăn?", "Thử quét thực đơn để tìm hiểu các món nhé."),
            ID to PushContent("Penasaran dengan menunya?", "Coba pindai menu untuk mengenal hidangannya."),
            TH to PushContent("สงสัยว่าเมนูนี้คืออะไร", "ลองสแกนเมนูเพื่อดูข้อมูลอาหารได้เลย"),
            RU to PushContent("Незнакомое блюдо?", "Можно отсканировать меню и узнать больше о блюдах."),
            ES to PushContent("¿No conoces un plato?", "Prueba a escanear el menú para saber más."),
        ),
        NotificationType.REVIEW_REMINDER to mapOf(
            KO to PushContent("{food} 어땠나요?", "방금 드신 {food}, 짧은 리뷰도 좋아요."),
            EN to PushContent("How was {food}?", "A few words about the {food} you just tried would be lovely."),
            JA to PushContent("{food}はいかがでしたか？", "先ほどの{food}、ひとこと感想を残しませんか。"),
            ZH_HANS to PushContent("{food}味道怎么样？", "刚吃的{food}，简单聊聊感受吧。"),
            ZH_HANT to PushContent("{food}吃起來怎麼樣？", "剛吃的{food}，簡單分享一下感想吧。"),
            VI to PushContent("{food} thế nào?", "Bạn thấy {food} vừa ăn thế nào? Chia sẻ vài lời nhé."),
            ID to PushContent("Bagaimana rasa {food}?", "Mau cerita sedikit tentang {food} yang baru kamu coba?"),
            TH to PushContent("{food} อร่อยไหม", "ลองเล่าสั้น ๆ ถึง {food} ที่เพิ่งทานกันไหม"),
            RU to PushContent("Как вам {food}?", "Поделитесь парой слов о блюде {food}, которое попробовали."),
            ES to PushContent("¿Qué tal {food}?", "¿Qué te pareció {food}? Cuéntanos en una reseña breve."),
        ),
        NotificationType.NEWS to LanguageCode.entries.associateWith { PushContent("{title}", "{body}") },
        NotificationType.MEAL_TIME to mapOf(
            KO to PushContent("식사 시간이에요", "뭘 먹을지 고민되면 근처 메뉴판을 스캔해 보세요."),
            EN to PushContent("Time to eat?", "Not sure what to order? Try scanning a menu nearby."),
            JA to PushContent("お食事の時間です", "何にするか迷ったら、近くのメニューをスキャンしてみませんか。"),
            ZH_HANS to PushContent("到饭点啦", "不知道吃什么？不妨扫一扫附近的菜单。"),
            ZH_HANT to PushContent("到用餐時間了", "不知道吃什麼？不妨掃描附近的菜單。"),
            VI to PushContent("Đến giờ ăn rồi", "Chưa biết ăn gì? Thử quét thực đơn quán gần bạn nhé."),
            ID to PushContent("Waktunya makan", "Bingung mau makan apa? Coba pindai menu di dekatmu."),
            TH to PushContent("ได้เวลาทานข้าวแล้ว", "ยังไม่รู้จะกินอะไร ลองสแกนเมนูร้านใกล้ ๆ ดูไหม"),
            RU to PushContent("Время поесть", "Не знаете, что выбрать? Отсканируйте меню поблизости."),
            ES to PushContent("¿Hora de comer?", "¿No sabes qué pedir? Prueba a escanear un menú cerca."),
        ),
    )

    val bySlot: Map<NotificationType, Map<MealSlot, Map<LanguageCode, PushContent>>> = mapOf(
        NotificationType.SCAN_SUGGESTION to mapOf(
            MealSlot.LUNCH to mapOf(
                KO to PushContent("점심 먹을 때 스캔해보세요", "메뉴판을 스캔하면 어떤 음식인지 바로 알 수 있어요."),
                EN to PushContent("Scanning the lunch menu?", "Scan the menu board to see what each dish is."),
                JA to PushContent("ランチのメニューをスキャン", "メニューをスキャンすると、どんな料理かすぐ分かります。"),
                ZH_HANS to PushContent("午餐时扫一扫菜单", "扫描菜单，马上知道每道菜是什么。"),
                ZH_HANT to PushContent("午餐時掃描菜單", "掃描菜單，馬上知道每道菜是什麼。"),
                VI to PushContent("Quét thực đơn bữa trưa nhé", "Quét bảng thực đơn để biết ngay từng món là gì."),
                ID to PushContent("Pindai menu makan siangmu", "Pindai papan menu untuk langsung tahu tiap hidangan."),
                TH to PushContent("มื้อกลางวันลองสแกนเมนูดูสิ", "สแกนป้ายเมนูเพื่อรู้ทันทีว่าแต่ละจานคืออะไร"),
                RU to PushContent("Сканируйте меню за обедом", "Отсканируйте меню и сразу узнайте, что это за блюда."),
                ES to PushContent("¿Almuerzo? Escanea el menú", "Escanea la carta y sabrás al instante qué es cada plato."),
            ),
            MealSlot.DINNER to mapOf(
                KO to PushContent("저녁 메뉴, 스캔해보세요", "저녁 메뉴판을 스캔하면 어떤 음식인지 바로 알 수 있어요."),
                EN to PushContent("Dinner time: scan the menu", "Scan the menu board to see what each dish is."),
                JA to PushContent("ディナーのメニューをスキャン", "メニューをスキャンすると、どんな料理かすぐ分かります。"),
                ZH_HANS to PushContent("晚餐时扫一扫菜单", "扫描菜单，马上知道每道菜是什么。"),
                ZH_HANT to PushContent("晚餐時掃描菜單", "掃描菜單，馬上知道每道菜是什麼。"),
                VI to PushContent("Quét thực đơn bữa tối nhé", "Quét bảng thực đơn để biết ngay từng món là gì."),
                ID to PushContent("Pindai menu makan malammu", "Pindai papan menu untuk langsung tahu tiap hidangan."),
                TH to PushContent("มื้อเย็นลองสแกนเมนูดูสิ", "สแกนป้ายเมนูเพื่อรู้ทันทีว่าแต่ละจานคืออะไร"),
                RU to PushContent("Сканируйте меню за ужином", "Отсканируйте меню и сразу узнайте, что это за блюда."),
                ES to PushContent("¿Cena? Escanea el menú", "Escanea la carta y sabrás al instante qué es cada plato."),
            ),
        ),
    )

    val optOutNotice: Map<LanguageCode, String> = mapOf(
        KO to "수신거부: 프로필 > 알림 설정",
        EN to "Turn off: Profile > Notification settings",
        JA to "通知をオフ: プロフィール > 通知設定",
        ZH_HANS to "关闭通知：个人资料 > 通知设置",
        ZH_HANT to "關閉通知：個人資料 > 通知設定",
        VI to "Tắt thông báo: Hồ sơ > Cài đặt thông báo",
        ID to "Matikan: Profil > Pengaturan notifikasi",
        TH to "ปิดรับ: โปรไฟล์ > การตั้งค่าการแจ้งเตือน",
        RU to "Отключить: Профиль > Настройки уведомлений",
        ES to "Desactivar: Perfil > Ajustes de notificaciones",
    )
}
