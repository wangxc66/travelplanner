-- A day is an ordered list: two items in the same trip/day may never occupy the same slot.
-- Application mutations use a two-phase temporary sequence before writing the final 0...n-1 order,
-- so swaps satisfy this immediate unique constraint on both PostgreSQL and H2.
ALTER TABLE itinerary_item
    ADD CONSTRAINT uk_itinerary_item_trip_day_seq UNIQUE (trip_id, day_index, seq);

