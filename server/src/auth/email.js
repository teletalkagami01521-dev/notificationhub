import { Resend } from 'resend'

let client = null
function getClient() {
    if (!client) client = new Resend(process.env.RESEND_API_KEY)
    return client
}

export async function sendOtpEmail(to, code) {
    const { error } = await getClient().emails.send({
        from: 'NotifyHub <onboarding@resend.dev>',
        to,
        subject: `${code} — your NotifyHub login code`,
        text: `Your login code is: ${code}\n\nExpires in 10 minutes. Do not share it.`,
        html: `
            <div style="font-family:system-ui,sans-serif;max-width:420px;margin:0 auto;padding:32px 24px">
              <div style="font-size:12px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;color:#7d8590;margin-bottom:20px">NotifyHub</div>
              <h1 style="font-size:26px;font-weight:700;letter-spacing:-1px;margin:0 0 10px;color:#1f2328">Your login code</h1>
              <p style="color:#57606a;font-size:15px;margin:0 0 28px;line-height:1.5">Enter this code in the NotifyHub dashboard. Expires in 10&nbsp;minutes.</p>
              <div style="background:#f6f8fa;border:1px solid #d0d7de;border-radius:10px;padding:24px;text-align:center;margin-bottom:28px">
                <span style="font-family:monospace;font-size:40px;font-weight:700;letter-spacing:10px;color:#1f2328">${code}</span>
              </div>
              <p style="color:#8c959f;font-size:13px;margin:0">If you didn't request this, ignore this email.</p>
            </div>`,
    })
    if (error) throw new Error(error.message)
}
