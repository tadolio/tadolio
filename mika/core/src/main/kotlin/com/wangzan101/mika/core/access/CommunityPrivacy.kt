// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

/** Exact privacy package denylist. Unknown apps remain available in community mode. */
object CommunityPrivacy {
    val packages: Set<String> = setOf(
        // Private messages, calls, contacts and mail.
        "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim",
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
        "jp.naver.line.android", "com.facebook.orca", "org.thoughtcrime.securesms",
        "com.alibaba.android.rimet", "com.ss.android.lark", "com.larksuite.suite", "com.Slack",
        "com.discord", "com.skype.raider", "com.viber.voip", "com.snapchat.android",
        "com.instagram.android", "com.facebook.katana", "com.twitter.android", "com.sina.weibo",
        "com.xingin.xhs", "com.zhiliaoapp.musically", "com.ss.android.ugc.aweme",
        "com.android.mms", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
        "com.android.contacts", "com.google.android.contacts", "com.samsung.android.app.contacts",
        "com.android.phone", "com.android.server.telecom", "com.android.incallui", "com.samsung.android.incallui",
        "com.android.email", "com.samsung.android.email.provider", "com.google.android.gm",
        "com.microsoft.office.outlook", "com.tencent.androidqqmail", "com.netease.mail",
        "com.netease.mobimail", "ch.protonmail.android", "com.yahoo.mobile.client.android.mail",
        // Money and wallets.
        "com.eg.android.AlipayGphone", "com.unionpay", "com.paypal.android.p2pmobile",
        "com.paypal.merchant.client", "com.binance.dev", "com.coinbase.android",
        "org.toshi", "com.wallet.crypto.trustapp", "io.metamask", "app.phantom",
        "im.token.app", "vip.mytokenpocket", "com.okinc.okex.gp", "com.okinc.okex",
        "com.icbc", "com.icbc.android", "com.chinamworld.main", "com.android.bankabc",
        "com.chinamworld.bocmbci", "cmb.pb", "com.bankcomm.Bankcomm", "cn.com.cmbc.newmbank",
        "com.citic.bank", "com.cebbank.mobile.cemb", "cn.com.spdb.mobilebank.per",
        "com.pingan.paces.ccms", "com.pingan.bank.pab", "com.cib.cibmb", "com.psbc.mbank",
        "com.hundsun.winner.pazq", "com.hexin.plat.android", "com.eastmoney.android.berlin",
        "com.android.dazhihui", "com.futu.nn", "com.tigerbrokers.stock", "com.robinhood.android",
        "com.chase.sig.android", "com.infonow.bofa", "com.wf.wellsfargomobile",
        "com.revolut.revolut", "com.transferwise.android", "com.konylabs.capitalone",
        // Passwords, authenticators, photos and Tadolio itself.
        "com.onepassword.android", "com.agilebits.onepassword", "com.x8bit.bitwarden",
        "com.dashlane", "com.lastpass.lpandroid", "keepass2android.keepass2android",
        "keepass2android.keepass2android_nonet",
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
        "com.beemdevelopment.aegis", "org.fedorahosted.freeotp", "com.samsung.android.samsungpass",
        "com.google.android.apps.photos", "com.android.gallery", "com.android.gallery3d",
        "com.sec.android.gallery3d", "com.miui.gallery", "com.coloros.gallery3d",
        "com.oplus.gallery", "com.vivo.gallery", "com.huawei.photos", "com.huawei.gallery",
        "com.tadolio.app", "com.tadolio.app.dev", "com.wangzan101.mika",
    )

    fun blocked(packageName: String, state: AppAccessState): Boolean =
        packageName in packages || packageName in state.blocked ||
            state.classifications[packageName]?.let {
                it.manual && it.category in setOf(AppCategory.Finance, AppCategory.Social)
            } == true
}
