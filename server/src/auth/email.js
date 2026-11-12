// Brevo transactional email — HTTP API, no SMTP, works on any host
export async function sendOtpEmail(to, code) {
    const res = await fetch('https://api.brevo.com/v3/smtp/email', {
        method: 'POST',
        headers: {
            'api-key': process.env.BREVO_API_KEY,
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            sender: { name: 'NotifyHub', email: process.env.BREVO_SENDER_EMAIL },
            to: [{ email: to }],
            subject: `${code} — your NotifyHub login code`,
            textContent: `Your login code is: ${code}\n\nExpires in 10 minutes. Do not share it.`,
            htmlContent: `
                <div style="font-family:system-ui,sans-serif;max-width:420px;margin:0 auto;padding:32px 24px">
                  <div style="font-size:12px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;color:#7d8590;margin-bottom:20px">NotifyHub</div>
                  <h1 style="font-size:26px;font-weight:700;letter-spacing:-1px;margin:0 0 10px;color:#1f2328">Your login code</h1>
                  <p style="color:#57606a;font-size:15px;margin:0 0 28px;line-height:1.5">Enter this code in the NotifyHub dashboard. Expires in 10&nbsp;minutes.</p>
                  <div style="background:#f6f8fa;border:1px solid #d0d7de;border-radius:10px;padding:24px;text-align:center;margin-bottom:28px">
                    <span style="font-family:monospace;font-size:40px;font-weight:700;letter-spacing:10px;color:#1f2328">${code}</span>
                  </div>
                  <p style="color:#8c959f;font-size:13px;margin:0">If you didn't request this, ignore this email.</p>
                </div>`,
        }),
    })
    if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        throw new Error(body.message || `Brevo error ${res.status}`)
    }
}
