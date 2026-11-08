#!/bin/bash
# VPN только для YouTube и Google: профиль маршрутизации Happ. Вызывается из tv route on.
R="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${STARWIND_HOME:-$HOME/Documents/Claude}"
export TV_HOST="${TV_IP:-$(cat "$DIR/tv-ip" 2>/dev/null || echo 192.168.31.237)}"
TVPY="$R/tools/venv/bin/python -I $R/tools/tv.py"
B64=$(python3 - <<'P'
import base64,json
p={"Name":"OnlyYouTube","GlobalProxy":"false",
"RemoteDNSType":"DoH","RemoteDNSDomain":"https://cloudflare-dns.com/dns-query","RemoteDNSIP":"1.1.1.1",
"DomesticDNSType":"DoH","DomesticDNSDomain":"https://common.dot.dns.yandex.net/dns-query","DomesticDNSIP":"77.88.8.8",
"ProxySites":["domain:youtube.com","domain:youtu.be","domain:googlevideo.com","domain:ytimg.com","domain:ggpht.com","domain:googleapis.com","domain:gstatic.com","domain:googleusercontent.com","domain:google.com","domain:youtube-nocookie.com","domain:withyoutube.com","domain:ifconfig.co"],
"ProxyIp":[],"DirectSites":[],"DirectIp":["10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","127.0.0.0/8"],
"BlockSites":[],"BlockIp":[],"DomainStrategy":"IPIfNonMatch","FakeDNS":"false"}
print(base64.b64encode(json.dumps(p,ensure_ascii=False).encode()).decode())
P
)
$TVPY "am start -a android.intent.action.VIEW -d happ://routing/onadd/$B64 -n su.happ.proxyutility/.feature.main.MainActivity" > /dev/null
echo ">>> На телевизоре в окне Маршрутизация выбери подписку и нажми Готово. Жду 30 секунд."
sleep 30
L='-a android.intent.action.VIEW -n su.happ.proxyutility/.feature.deeplink.DeeplinkInterceptorActivity'
$TVPY "am start $L -d happ://disconnect_without_ui" > /dev/null; sleep 4
$TVPY "am start $L -d happ://connect_without_ui" > /dev/null; sleep 6
$TVPY 'am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/.IosHome' > /dev/null
echo "Готово: через VPN идут только YouTube и Google. Happ это запомнил, повторять не нужно."
