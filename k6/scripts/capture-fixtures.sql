DROP PROCEDURE IF EXISTS kbap_capture_load_fixture_state;

DELIMITER //
CREATE PROCEDURE kbap_capture_load_fixture_state()
BEGIN
    DECLARE snapshot_json JSON;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF @run_id IS NULL OR @run_id NOT REGEXP '^[0-9A-Za-z._-]+-[0-9A-Za-z._-]+$' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '@run_id must match the campaign-target format';
    END IF;
    IF @target IS NULL OR @target NOT REGEXP '^[0-9A-Za-z._-]+$' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '@target is required';
    END IF;
    IF @blocked_member_ids_json IS NULL OR JSON_TYPE(JSON_EXTRACT(@blocked_member_ids_json, '$')) <> 'ARRAY'
        OR @bookmark_food_ids_json IS NULL OR JSON_TYPE(JSON_EXTRACT(@bookmark_food_ids_json, '$')) <> 'ARRAY'
        OR @review_ids_json IS NULL OR JSON_TYPE(JSON_EXTRACT(@review_ids_json, '$')) <> 'ARRAY' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture ID variables must be JSON arrays';
    END IF;

    START TRANSACTION WITH CONSISTENT SNAPSHOT;

    SELECT JSON_OBJECT(
        'version', 1,
        'runId', @run_id,
        'target', @target,
        'fixtureBlockedMemberIds', JSON_EXTRACT(@blocked_member_ids_json, '$'),
        'fixtureBookmarkFoodIds', JSON_EXTRACT(@bookmark_food_ids_json, '$'),
        'fixtureReviewIds', JSON_EXTRACT(@review_ids_json, '$'),
        'memberProfile', JSON_OBJECT(
            'nickname', member.nickname,
            'spicinessPreference', member.spiciness_preference,
            'countryCode', member.country_code,
            'profileImageUrl', member.profile_image_url,
            'currency', member.currency,
            'avoidanceSubstanceCodes', member.avoidance_substance_codes,
            'dietCategories', member.diet_categories
        ),
        'memberCounters', JSON_OBJECT(
            'scanCount', member.scan_count,
            'scanUnlocked', member.scan_unlocked,
            'reviewCount', member.review_count,
            'uniqueReviewedFoodCount', member.unique_reviewed_food_count
        ),
        'memberBlocks', COALESCE((
            SELECT JSON_ARRAYAGG(JSON_OBJECT('id', block.id, 'blockedMemberId', block.blocked_member_id, 'status', block.status))
            FROM member_block block
            JOIN JSON_TABLE(@blocked_member_ids_json, '$[*]' COLUMNS (fixture_id BIGINT PATH '$')) fixture
              ON fixture.fixture_id = block.blocked_member_id
            WHERE block.blocker_member_id = 35
        ), JSON_ARRAY()),
        'bookmarks', COALESCE((
            SELECT JSON_ARRAYAGG(JSON_OBJECT('id', bookmark.id, 'foodId', bookmark.food_id, 'status', bookmark.status))
            FROM bookmark
            JOIN JSON_TABLE(@bookmark_food_ids_json, '$[*]' COLUMNS (fixture_id BIGINT PATH '$')) fixture
              ON fixture.fixture_id = bookmark.food_id
            WHERE bookmark.member_id = 35
        ), JSON_ARRAY()),
        'reviewLikes', COALESCE((
            SELECT JSON_ARRAYAGG(JSON_OBJECT('id', review_like.id, 'reviewId', review_like.review_id, 'status', review_like.status))
            FROM review_like
            JOIN JSON_TABLE(@review_ids_json, '$[*]' COLUMNS (fixture_id BIGINT PATH '$')) fixture
              ON fixture.fixture_id = review_like.review_id
            WHERE review_like.member_id = 35
        ), JSON_ARRAY()),
        'reviews', COALESCE((
            SELECT JSON_ARRAYAGG(JSON_OBJECT(
                'id', review.id,
                'rating', review.rating,
                'servingSpeedRating', review.serving_speed_rating,
                'staffKindnessRating', review.staff_kindness_rating,
                'content', review.content,
                'imageRefs', review.image_refs,
                'placeSource', review.place_source,
                'placeName', review.place_name,
                'placeAddress', review.place_address,
                'placeLatitude', review.place_latitude,
                'placeLongitude', review.place_longitude,
                'placeId', review.place_id,
                'placeAddressKo', review.place_address_ko,
                'status', review.status,
                'version', review.version
            ))
            FROM food_review review
            JOIN JSON_TABLE(@review_ids_json, '$[*]' COLUMNS (fixture_id BIGINT PATH '$')) fixture
              ON fixture.fixture_id = review.id
            WHERE review.member_id = 35
        ), JSON_ARRAY()),
        'rankingEventHighWatermark', (SELECT COALESCE(MAX(id), 0) FROM member_ranking_event WHERE member_id = 35),
        'scanHistoryHighWatermark', (SELECT COALESCE(MAX(id), 0) FROM scan_history WHERE member_id = 35),
        'foodHighWatermark', (SELECT COALESCE(MAX(id), 0) FROM food),
        'contentOutboxHighWatermark', (SELECT COALESCE(MAX(id), 0) FROM food_content_outbox)
    ) INTO snapshot_json
    FROM member
    WHERE id = 35 AND member_status = 'ACTIVE' AND status = 'ACTIVE';

    IF snapshot_json IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'active member 35 is required';
    END IF;

    COMMIT;
    SELECT REPLACE(TO_BASE64(CONVERT(snapshot_json USING utf8mb4)), '\n', '') AS snapshot_base64;
END//
DELIMITER ;

CALL kbap_capture_load_fixture_state();
DROP PROCEDURE kbap_capture_load_fixture_state;
