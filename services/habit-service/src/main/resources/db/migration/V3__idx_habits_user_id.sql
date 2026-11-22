-- R1-17c (HF-27): HF-26 Q3 (HabitRepository.findByUserId) was a Seq Scan over the whole habits heap.
-- IF NOT EXISTS: idempotent like V1, harmless if the index was created by hand.
-- Not CONCURRENTLY: Flyway runs this inside a transaction, where CREATE INDEX CONCURRENTLY is not allowed.
-- The plain build takes a SHARE lock (blocks writes to habits, not reads) for the length of the build.
CREATE INDEX IF NOT EXISTS habits_user_id_idx ON habits (user_id);
