import nodemailer from 'nodemailer'

let transporter = null

function getTransporter() {
    if (!transporter) {
        transporter = nodemailer.createTransport({
            service: 'gmail',
            auth: {
                user: process.env.GMAIL_USER,
                pass: process.env.GMAIL_APP_PASSWORD,
            },
        })
    }
    return transporter
}

export async function sendOtpEmail(to, code) {
    await getTransporter().sendMail({
        from: `NotifyHub <${process.env.GMAIL_USER}>`,
        to,
        subject: `${code} — your NotifyHub login code`,
        text: `Your login code is: ${code}\n\nThis code expires in 10 minutes. Do not share it.`,
        html: `
            <div style="font-family:system-ui,sans-serif;max-width:400px;margin:0 auto;padding:32px 24px">
              <div style="font-size:13px;font-weight:600;letter-spacing:.05em;text-transform:uppercase;color:#7d8590;margin-bottom:16px">NotifyHub</div>
              <h1 style="font-size:28px;font-weight:700;letter-spacing:-1px;margin:0 0 8px">Your login code</h1>
              <p style="color:#57606a;font-size:15px;margin:0 0 28px">Enter this code in the NotifyHub dashboard. It expires in 10 minutes.</p>
              <div style="background:#f6f8fa;border:1px solid #d0d7de;border-radius:8px;padding:20px;text-align:center;margin-bottom:24px">
                <span style="font-family:monospace;font-size:36px;font-weight:700;letter-spacing:8px;color:#1f2328">${code}</span>
              </div>
              <p style="color:#8c959f;font-size:13px;margin:0">If you didn't request this, ignore this email.</p>
            </div>`,
    })
}
