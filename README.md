# Fridah Online Net — Backend + App

Backend for the **Fridah Tech** Android app (`onlinehotspottech.apk`) and the
admin control center. Deploy target: **https://fridahyobbytech.onrender.com**

- Real auth: phone number + 4-digit PIN + device IMEI
- Free-net registry: **1 session/day, 20 min, 40 MB cap, 3 days per device (IMEI)**
- Payments: **SwiftWallet M-PESA STK Push** + signed webhook + **live SSE confirmation (no polling timeouts)**
- Admin panel at **/admin**: users, live payments, maintenance mode, prices, messages, reels, keys
- App update feed: reads the latest GitHub release of `yobbymeg/fridah-tech`

---

## Deploy on Render (5 minutes)

1. Push this repo to GitHub (or import it in Render directly).
2. On https://dashboard.render.com → **New +** → **Web Service** → connect this repo.
3. Settings:
   - **Name**: `fridahyobbytech`  (this gives you the URL fridahyobbytech.onrender.com)
   - **Runtime**: Node
   - **Build Command**: `npm install`
   - **Start Command**: `node server.js`
   - **Instance type**: Free (works; spins down when idle — wake-up takes ~30 s)
4. Add **Environment variables**:

| Key | Value |
| --- | --- |
| `PUBLIC_URL` | `https://fridahyobbytech.onrender.com` |
| `SWIFTWALLET_API_KEY` | your SwiftWallet live key (`sk_live_…`) |
| `SWIFTWALLET_WEBHOOK_SECRET` | your SwiftWallet webhook secret (`whsec_…`) |
| `ADMIN_PASSWORD` | your admin password (default `Fridah@2026`) |
| `DATA_DIR` | `/tmp/fridah-data` (or a mounted disk path) |

5. Deploy. Then open **https://fridahyobbytech.onrender.com/admin** and log in.

### Connect SwiftWallet webhooks
In your SwiftWallet dashboard, set the transaction webhook URL to:

```
https://fridahyobbytech.onrender.com/api/webhook/swiftwallet
```

Every webhook is verified with **HMAC-SHA256** (`X-SwiftWallet-Signature` header)
using your `whsec_…` secret, so wrong signatures are rejected automatically.

> Keys can also be set from the admin panel (Settings → SwiftWallet) if you
> prefer not to use env vars. Env vars always win if both are set.

---

## Admin panel (/admin)

- **Dashboard** — users, active packages, revenue today/total, free-net usage today
- **Users** — phone, device, IMEI, joined, last seen, free days used, package, spend. Search + CSV export.
- **Payments (live)** — instant SSE feed: every STK push appears the second it is created and flips to COMPLETED/FAILED live. No refresh, no timeout.
- **Settings** —
  - Maintenance mode ON/OFF (every installed app shows "Under maintenance" instantly)
  - Free net limits (minutes / MB cap / days per IMEI)
  - Packages & prices (KES)
  - App messages (free net granted / free net over / SIM blocked / admin phone)
  - SwiftWallet keys, GitHub repo for updates, manual APK URL, Reels links

Default admin password: **Fridah@2026** — change it with the `ADMIN_PASSWORD` env var.

---

## App ↔ backend API

| Endpoint | Purpose |
| --- | --- |
| `GET /api/config` | maintenance, free-net limits, packages, reels, messages, update info |
| `POST /api/auth/register` | `{phone, pin, imei, device}` → token |
| `POST /api/auth/login` | `{phone, pin, imei, device}` → token |
| `GET /api/me` | user + active package |
| `POST /api/freenet/start` | IMEI-limited free net (20 min/day, 3 days) |
| `POST /api/freenet/stop` | closes the day's session |
| `POST /api/pay/initiate` | SwiftWallet STK push for a package |
| `GET /api/pay/stream/:id` | **SSE live payment status** (no timeout) |
| `GET /api/pay/status/:id` | polling fallback |
| `POST /api/webhook/swiftwallet` | signed webhook → grants package, broadcasts SSE |
| `/api/admin/*` | admin API (header `X-Admin-Key`) |

---

## Publishing an app update

The app checks `GET /api/config` on every open. The backend resolves the latest
release from the GitHub repo (`yobbymeg/fridah-tech` by default, configurable in
Settings). Tag releases like:

```
v2.1+3        (versionName+versionCode)
```

and attach `onlinehotspottech.apk` as a release asset. Devices running an older
versionCode show **"New version available — UPDATE NOW"** and download the APK
from GitHub automatically.

---

## Data persistence (important on Render free tier)

Data lives in a JSON file at `DATA_DIR` (default `./data/data.json`). Render
free instances have an **ephemeral disk**: users/payments reset on every
redeploy or restart. Options:

1. Attach a Render **Disk** (paid) and set `DATA_DIR` to the mount path — fully persistent.
2. Use `/tmp/fridah-data` — survives restarts, resets on redeploys.
3. Use **Users → Export users CSV** regularly as a backup.

---

## Android app source

`app-source/` contains the complete native Java app (splash → SIM detect →
auto VPN → billing UI). Build with:

```bash
bash scripts/build_apk.sh       # needs Android build-tools 34 + platform 34
python3 scripts/make_icon.py    # regenerates the launcher icon
```

Tunnel stack: VpnService → userspace TCP/IP (`TcpStack`) → payload-injected
proxy socket → SSH (`JSch`). Airtel config: `d16l6nb4do0zu4.cloudfront.net:443`
via `secure.3gdirectpay.com:80`. Safaricom config: `1.kevinredm99.shop:80` via
`odi.site:80`, switching to `solidicon.ke-gamification-service.betika.com:80`
on 302, and reporting "free net is over" after a second 302.
