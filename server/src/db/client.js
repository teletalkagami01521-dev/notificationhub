import pg from 'pg'

const { Pool } = pg

export const db = new Pool({
    connectionString: process.env.DATABASE_URL,
    ssl: { rejectUnauthorized: false },  // Neon: sslmode=verify-full handled via connection string
    max: 5,                              // stay within Neon free tier connection limit
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 5_000,
})
