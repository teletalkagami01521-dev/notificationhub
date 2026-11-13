import crypto from 'crypto'

function sign(params) {
    const str = Object.entries(params)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([k, v]) => `${k}=${v}`)
        .join('&')
    return crypto.createHash('sha1').update(str + process.env.CLOUDINARY_API_SECRET).digest('hex')
}

function cloudUrl(path) {
    return `https://api.cloudinary.com/v1_1/${process.env.CLOUDINARY_CLOUD_NAME}/${path}`
}

function ready() {
    return !!(process.env.CLOUDINARY_CLOUD_NAME && process.env.CLOUDINARY_API_KEY && process.env.CLOUDINARY_API_SECRET)
}

export async function uploadImage(base64DataUrl) {
    if (!ready()) return null
    const timestamp = Math.floor(Date.now() / 1000)
    const folder    = 'notifyhub'
    const signature = sign({ folder, timestamp })
    try {
        const res = await fetch(cloudUrl('image/upload'), {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({
                file:      base64DataUrl,
                api_key:   process.env.CLOUDINARY_API_KEY,
                timestamp, folder, signature
            })
        })
        if (!res.ok) return null
        const data = await res.json()
        return data.secure_url ?? null
    } catch {
        return null
    }
}

export async function deleteImage(imageUrl) {
    if (!ready() || !imageUrl) return
    // Extract public_id from URL: .../upload/v123/notifyhub/abc.jpg → notifyhub/abc
    const match = imageUrl.match(/\/upload\/(?:v\d+\/)?(.+)\.[^.]+$/)
    if (!match) return
    const publicId  = match[1]
    const timestamp = Math.floor(Date.now() / 1000)
    const signature = sign({ public_id: publicId, timestamp })
    fetch(cloudUrl('image/destroy'), {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({
            public_id: publicId,
            api_key:   process.env.CLOUDINARY_API_KEY,
            timestamp, signature
        })
    }).catch(() => {})
}
