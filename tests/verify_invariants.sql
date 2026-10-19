-- Run read-only against the application database after a completed experiment.
SELECT count(*) AS oversold_event_seats FROM
 (SELECT event_id,label,count(*) FROM booking_item GROUP BY event_id,label HAVING count(*)>1) violations;
SELECT count(*) AS booked_inventory_mismatches FROM booking_item i JOIN seat s USING(event_id,label)
 WHERE s.booking_id IS DISTINCT FROM i.booking_id;
SELECT count(*) AS overlapping_live_hold_claims FROM
 (SELECT i.event_id,i.label,count(*) FROM hold_item i JOIN hold h ON h.id=i.hold_id
 WHERE h.status='HELD' AND h.expires_at>clock_timestamp()
 GROUP BY i.event_id,i.label HAVING count(*)>1) violations;
SELECT count(*) AS partial_booked_holds FROM hold h WHERE h.status='BOOKED' AND
 (SELECT count(*) FROM hold_item WHERE hold_id=h.id) <>
 (SELECT count(*) FROM booking_item i JOIN booking b ON b.id=i.booking_id WHERE b.hold_id=h.id);
SELECT count(*) AS payment_booking_mismatches FROM checkout c JOIN payment p ON p.checkout_id=c.id
 WHERE c.status<>p.status OR (c.status='SUCCEEDED' AND c.booking_id IS NULL);
