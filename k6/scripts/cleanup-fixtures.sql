DROP PROCEDURE IF EXISTS kbap_cleanup_load_fixtures;

DELIMITER //
CREATE PROCEDURE kbap_cleanup_load_fixtures()
BEGIN
    DECLARE escaped_run_id VARCHAR(255);
    DECLARE snapshot_json JSON;
    DECLARE snapshot_run_id VARCHAR(255);
    DECLARE snapshot_target VARCHAR(255);
    DECLARE ranking_event_high_watermark BIGINT;
    DECLARE scan_history_high_watermark BIGINT;
    DECLARE food_high_watermark BIGINT;
    DECLARE content_outbox_high_watermark BIGINT;
    DECLARE review_index INT DEFAULT 0;
    DECLARE review_snapshot JSON;
    DECLARE expected_count INT DEFAULT 0;
    DECLARE actual_count INT DEFAULT 0;
    DECLARE residual_count INT DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        DROP TEMPORARY TABLE IF EXISTS load_review_ids;
        DROP TEMPORARY TABLE IF EXISTS load_order_ids;
        DROP TEMPORARY TABLE IF EXISTS load_scan_history_ids;
        DROP TEMPORARY TABLE IF EXISTS load_candidate_food_ids;
        DROP TEMPORARY TABLE IF EXISTS load_deletable_food_ids;
        DROP TEMPORARY TABLE IF EXISTS load_object_cleanup_paths;
        RESIGNAL;
    END;

    IF @run_id IS NULL OR @run_id NOT REGEXP '^[0-9A-Za-z._-]+-[0-9A-Za-z._-]+$' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '@run_id must match the campaign-target format';
    END IF;
    IF @target IS NULL OR @target NOT REGEXP '^[0-9A-Za-z._-]+$' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '@target is required';
    END IF;
    IF @snapshot_base64 IS NULL OR @snapshot_base64 = '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '@snapshot_base64 is required';
    END IF;

    SET snapshot_json = CONVERT(FROM_BASE64(@snapshot_base64) USING utf8mb4);
    IF snapshot_json IS NULL OR JSON_VALID(snapshot_json) = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '@snapshot_base64 must contain valid snapshot JSON';
    END IF;
    SET snapshot_run_id = JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.runId'));
    SET snapshot_target = JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.target'));
    IF BINARY snapshot_run_id <> BINARY @run_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'snapshot run ID does not match cleanup run ID';
    END IF;
    IF BINARY snapshot_target <> BINARY @target THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'snapshot target does not match cleanup target';
    END IF;
    SET escaped_run_id = REPLACE(@run_id, '_', '=_');
    SET ranking_event_high_watermark = CAST(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.rankingEventHighWatermark')) AS UNSIGNED);
    SET scan_history_high_watermark = CAST(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.scanHistoryHighWatermark')) AS UNSIGNED);
    SET food_high_watermark = CAST(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.foodHighWatermark')) AS UNSIGNED);
    SET content_outbox_high_watermark = CAST(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.contentOutboxHighWatermark')) AS UNSIGNED);

    START TRANSACTION;

    CREATE TEMPORARY TABLE load_review_ids (review_id BIGINT PRIMARY KEY);
    CREATE TEMPORARY TABLE load_order_ids (order_id BIGINT PRIMARY KEY);
    CREATE TEMPORARY TABLE load_scan_history_ids (scan_history_id BIGINT PRIMARY KEY, food_id BIGINT NULL);
    CREATE TEMPORARY TABLE load_candidate_food_ids (food_id BIGINT PRIMARY KEY);
    CREATE TEMPORARY TABLE load_deletable_food_ids (food_id BIGINT PRIMARY KEY);
    CREATE TEMPORARY TABLE load_object_cleanup_paths (object_cleanup_path VARCHAR(512) PRIMARY KEY);

    IF @target IN ('member-profile-v1', 'member-profile-v11') THEN
        UPDATE member
        SET nickname = IF(JSON_TYPE(JSON_EXTRACT(snapshot_json, '$.memberProfile.nickname')) = 'NULL', NULL,
                JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.memberProfile.nickname'))),
            spiciness_preference = JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.memberProfile.spicinessPreference')),
            country_code = IF(JSON_TYPE(JSON_EXTRACT(snapshot_json, '$.memberProfile.countryCode')) = 'NULL', NULL,
                JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.memberProfile.countryCode'))),
            profile_image_url = IF(JSON_TYPE(JSON_EXTRACT(snapshot_json, '$.memberProfile.profileImageUrl')) = 'NULL', NULL,
                JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.memberProfile.profileImageUrl'))),
            currency = IF(JSON_TYPE(JSON_EXTRACT(snapshot_json, '$.memberProfile.currency')) = 'NULL', NULL,
                JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.memberProfile.currency'))),
            avoidance_substance_codes = JSON_EXTRACT(snapshot_json, '$.memberProfile.avoidanceSubstanceCodes'),
            diet_categories = JSON_EXTRACT(snapshot_json, '$.memberProfile.dietCategories')
        WHERE id = 35;

    ELSEIF @target IN ('member-block', 'member-unblock') THEN
        DELETE current_block
        FROM member_block current_block
        JOIN JSON_TABLE(snapshot_json, '$.fixtureBlockedMemberIds[*]' COLUMNS (fixture_id BIGINT PATH '$')) fixture
          ON fixture.fixture_id = current_block.blocked_member_id
        LEFT JOIN JSON_TABLE(snapshot_json, '$.memberBlocks[*]' COLUMNS (snapshot_id BIGINT PATH '$.id')) original
          ON original.snapshot_id = current_block.id
        WHERE current_block.blocker_member_id = 35 AND original.snapshot_id IS NULL;
        UPDATE member_block current_block
        JOIN JSON_TABLE(snapshot_json, '$.memberBlocks[*]' COLUMNS (
            snapshot_id BIGINT PATH '$.id', snapshot_status VARCHAR(20) PATH '$.status'
        )) original ON original.snapshot_id = current_block.id
        SET current_block.status = original.snapshot_status
        WHERE current_block.blocker_member_id = 35;
        SELECT JSON_LENGTH(JSON_EXTRACT(snapshot_json, '$.memberBlocks')) INTO expected_count;
        SELECT COUNT(*) INTO actual_count
        FROM member_block current_block
        JOIN JSON_TABLE(snapshot_json, '$.memberBlocks[*]' COLUMNS (snapshot_id BIGINT PATH '$.id')) original
          ON original.snapshot_id = current_block.id
        WHERE current_block.blocker_member_id = 35;
        IF actual_count <> expected_count THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'member block snapshot restore was incomplete';
        END IF;

    ELSEIF @target IN ('bookmark-add', 'bookmark-remove') THEN
        DELETE current_bookmark
        FROM bookmark current_bookmark
        JOIN JSON_TABLE(snapshot_json, '$.fixtureBookmarkFoodIds[*]' COLUMNS (fixture_id BIGINT PATH '$')) fixture
          ON fixture.fixture_id = current_bookmark.food_id
        LEFT JOIN JSON_TABLE(snapshot_json, '$.bookmarks[*]' COLUMNS (snapshot_id BIGINT PATH '$.id')) original
          ON original.snapshot_id = current_bookmark.id
        WHERE current_bookmark.member_id = 35 AND original.snapshot_id IS NULL;
        UPDATE bookmark current_bookmark
        JOIN JSON_TABLE(snapshot_json, '$.bookmarks[*]' COLUMNS (
            snapshot_id BIGINT PATH '$.id', snapshot_status VARCHAR(20) PATH '$.status'
        )) original ON original.snapshot_id = current_bookmark.id
        SET current_bookmark.status = original.snapshot_status
        WHERE current_bookmark.member_id = 35;
        SELECT JSON_LENGTH(JSON_EXTRACT(snapshot_json, '$.bookmarks')) INTO expected_count;
        SELECT COUNT(*) INTO actual_count
        FROM bookmark current_bookmark
        JOIN JSON_TABLE(snapshot_json, '$.bookmarks[*]' COLUMNS (snapshot_id BIGINT PATH '$.id')) original
          ON original.snapshot_id = current_bookmark.id
        WHERE current_bookmark.member_id = 35;
        IF actual_count <> expected_count THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'bookmark snapshot restore was incomplete';
        END IF;

    ELSEIF @target IN ('review-like', 'review-unlike') THEN
        DELETE current_like
        FROM review_like current_like
        JOIN JSON_TABLE(snapshot_json, '$.fixtureReviewIds[*]' COLUMNS (fixture_id BIGINT PATH '$')) fixture
          ON fixture.fixture_id = current_like.review_id
        LEFT JOIN JSON_TABLE(snapshot_json, '$.reviewLikes[*]' COLUMNS (snapshot_id BIGINT PATH '$.id')) original
          ON original.snapshot_id = current_like.id
        WHERE current_like.member_id = 35 AND original.snapshot_id IS NULL;
        UPDATE review_like current_like
        JOIN JSON_TABLE(snapshot_json, '$.reviewLikes[*]' COLUMNS (
            snapshot_id BIGINT PATH '$.id', snapshot_status VARCHAR(20) PATH '$.status'
        )) original ON original.snapshot_id = current_like.id
        SET current_like.status = original.snapshot_status
        WHERE current_like.member_id = 35;
        SELECT JSON_LENGTH(JSON_EXTRACT(snapshot_json, '$.reviewLikes')) INTO expected_count;
        SELECT COUNT(*) INTO actual_count
        FROM review_like current_like
        JOIN JSON_TABLE(snapshot_json, '$.reviewLikes[*]' COLUMNS (snapshot_id BIGINT PATH '$.id')) original
          ON original.snapshot_id = current_like.id
        WHERE current_like.member_id = 35;
        IF actual_count <> expected_count THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'review like snapshot restore was incomplete';
        END IF;

    ELSEIF @target IN ('review-update', 'review-delete') THEN
        DELETE ranking_event
        FROM member_ranking_event ranking_event
        JOIN JSON_TABLE(snapshot_json, '$.fixtureReviewIds[*]' COLUMNS (fixture_id BIGINT PATH '$')) fixture
          ON fixture.fixture_id = ranking_event.review_id
        WHERE ranking_event.member_id = 35 AND ranking_event.id > ranking_event_high_watermark;

        SET expected_count = JSON_LENGTH(JSON_EXTRACT(snapshot_json, '$.reviews'));
        SELECT COUNT(*) INTO actual_count
        FROM food_review review
        JOIN JSON_TABLE(snapshot_json, '$.reviews[*]' COLUMNS (snapshot_id BIGINT PATH '$.id')) original
          ON original.snapshot_id = review.id
        WHERE review.member_id = 35;
        IF actual_count <> expected_count THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'review snapshot rows are missing';
        END IF;
        SET review_index = 0;
        WHILE review_index < expected_count DO
            SET review_snapshot = JSON_EXTRACT(snapshot_json, CONCAT('$.reviews[', review_index, ']'));
            UPDATE food_review
            SET rating = JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.rating')),
                serving_speed_rating = JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.servingSpeedRating')),
                staff_kindness_rating = JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.staffKindnessRating')),
                content = IF(JSON_TYPE(JSON_EXTRACT(review_snapshot, '$.content')) = 'NULL', NULL,
                    JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.content'))),
                image_refs = IF(JSON_TYPE(JSON_EXTRACT(review_snapshot, '$.imageRefs')) = 'NULL', NULL,
                    JSON_EXTRACT(review_snapshot, '$.imageRefs')),
                place_source = IF(JSON_TYPE(JSON_EXTRACT(review_snapshot, '$.placeSource')) = 'NULL', NULL,
                    JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.placeSource'))),
                place_name = IF(JSON_TYPE(JSON_EXTRACT(review_snapshot, '$.placeName')) = 'NULL', NULL,
                    JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.placeName'))),
                place_address = IF(JSON_TYPE(JSON_EXTRACT(review_snapshot, '$.placeAddress')) = 'NULL', NULL,
                    JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.placeAddress'))),
                place_latitude = IF(JSON_TYPE(JSON_EXTRACT(review_snapshot, '$.placeLatitude')) = 'NULL', NULL,
                    JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.placeLatitude'))),
                place_longitude = IF(JSON_TYPE(JSON_EXTRACT(review_snapshot, '$.placeLongitude')) = 'NULL', NULL,
                    JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.placeLongitude'))),
                place_id = IF(JSON_TYPE(JSON_EXTRACT(review_snapshot, '$.placeId')) = 'NULL', NULL,
                    JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.placeId'))),
                place_address_ko = IF(JSON_TYPE(JSON_EXTRACT(review_snapshot, '$.placeAddressKo')) = 'NULL', NULL,
                    JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.placeAddressKo'))),
                status = JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.status')),
                version = JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.version'))
            WHERE id = JSON_UNQUOTE(JSON_EXTRACT(review_snapshot, '$.id')) AND member_id = 35;
            SET review_index = review_index + 1;
        END WHILE;
        UPDATE member
        SET review_count = JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.memberCounters.reviewCount')),
            unique_reviewed_food_count = JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.memberCounters.uniqueReviewedFoodCount')),
            scan_unlocked = JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.memberCounters.scanUnlocked'))
        WHERE id = 35;

    ELSEIF @target = 'review-create' THEN
        INSERT INTO load_review_ids (review_id)
        SELECT id FROM food_review
        WHERE member_id = 35
          AND BINARY content LIKE BINARY CONCAT('%[load:', escaped_run_id, ']%') ESCAPE '=';
        DELETE report_row FROM report report_row
        JOIN load_review_ids fixture ON fixture.review_id = report_row.target_id
        WHERE report_row.target_type = 'REVIEW';
        DELETE review_like_row FROM review_like review_like_row
        JOIN load_review_ids fixture ON fixture.review_id = review_like_row.review_id;
        DELETE ranking_event FROM member_ranking_event ranking_event
        JOIN load_review_ids fixture ON fixture.review_id = ranking_event.review_id;
        DELETE review FROM food_review review
        JOIN load_review_ids fixture ON fixture.review_id = review.id;
        UPDATE member
        SET review_count = JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.memberCounters.reviewCount')),
            unique_reviewed_food_count = JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.memberCounters.uniqueReviewedFoodCount')),
            scan_unlocked = JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.memberCounters.scanUnlocked'))
        WHERE id = 35;

    ELSEIF @target = 'report-create' THEN
        DELETE FROM report
        WHERE reporter_member_id = 35
          AND BINARY detail LIKE BINARY CONCAT('%[load:', escaped_run_id, ']%') ESCAPE '=';

    ELSEIF @target IN ('order-create-no-location', 'order-create-location') THEN
        INSERT INTO load_order_ids (order_id)
        SELECT DISTINCT placed_order.id
        FROM orders placed_order
        JOIN order_item item ON item.order_id = placed_order.id
        WHERE placed_order.member_id = 35
          AND BINARY item.menu_name LIKE BINARY CONCAT('%[load:', escaped_run_id, ']%') ESCAPE '=';
        DELETE item FROM order_item item
        JOIN load_order_ids fixture ON fixture.order_id = item.order_id;
        DELETE placed_order FROM orders placed_order
        JOIN load_order_ids fixture ON fixture.order_id = placed_order.id;

    ELSEIF @target = 'image-complete' THEN
        INSERT IGNORE INTO load_object_cleanup_paths (object_cleanup_path)
        SELECT object_path FROM uploaded_image
        WHERE member_id = 35
          AND BINARY object_path LIKE BINARY CONCAT('%[load:', escaped_run_id, ']%') ESCAPE '=';
        DELETE FROM uploaded_image
        WHERE member_id = 35
          AND BINARY object_path LIKE BINARY CONCAT('%[load:', escaped_run_id, ']%') ESCAPE '=';

    ELSEIF @target IN ('scan-v1', 'scan-v2-krw', 'scan-v2-usd') THEN
        INSERT INTO load_scan_history_ids (scan_history_id, food_id)
        SELECT scan_history.id, scan_history.food_id
        FROM scan_history
        WHERE scan_history.member_id = 35
          AND scan_history.id > scan_history_high_watermark;
        INSERT IGNORE INTO load_candidate_food_ids (food_id)
        SELECT DISTINCT scan_fixture.food_id
        FROM load_scan_history_ids scan_fixture
        JOIN food candidate_food ON candidate_food.id = scan_fixture.food_id
        WHERE scan_fixture.food_id IS NOT NULL
          AND candidate_food.id > food_high_watermark
          AND candidate_food.content_status = 'FAILED';
        INSERT INTO load_deletable_food_ids (food_id)
        SELECT candidate.food_id
        FROM load_candidate_food_ids candidate
        WHERE NOT EXISTS (SELECT 1 FROM scan_history ref WHERE ref.food_id = candidate.food_id
            AND NOT (ref.member_id = 35 AND ref.id > scan_history_high_watermark))
          AND NOT EXISTS (SELECT 1 FROM bookmark ref WHERE ref.food_id = candidate.food_id)
          AND NOT EXISTS (SELECT 1 FROM food_review ref WHERE ref.food_id = candidate.food_id)
          AND NOT EXISTS (SELECT 1 FROM food_avoidance_substance ref WHERE ref.food_id = candidate.food_id)
          AND NOT EXISTS (SELECT 1 FROM order_item ref WHERE ref.food_id = candidate.food_id)
          AND NOT EXISTS (SELECT 1 FROM food_vector_outbox ref WHERE ref.food_id = candidate.food_id)
          AND NOT EXISTS (SELECT 1 FROM image_batch_item ref WHERE ref.food_id = candidate.food_id)
          AND NOT EXISTS (SELECT 1 FROM community_post ref
              WHERE ref.food_ids IS NOT NULL AND JSON_CONTAINS(ref.food_ids, CAST(candidate.food_id AS JSON), '$'))
          AND NOT EXISTS (SELECT 1 FROM food_content_outbox ref WHERE ref.food_id = candidate.food_id
              AND NOT (ref.id > content_outbox_high_watermark AND ref.outbox_status = 'PENDING'));
        INSERT IGNORE INTO load_object_cleanup_paths (object_cleanup_path)
        SELECT candidate_food.image_ref
        FROM food candidate_food
        JOIN load_deletable_food_ids fixture ON fixture.food_id = candidate_food.id
        WHERE candidate_food.image_ref IS NOT NULL AND candidate_food.image_ref <> '';
        DELETE outbox FROM food_content_outbox outbox
        JOIN load_deletable_food_ids fixture ON fixture.food_id = outbox.food_id
        WHERE outbox.id > content_outbox_high_watermark AND outbox.outbox_status = 'PENDING';
        DELETE scan_history FROM scan_history
        JOIN load_scan_history_ids fixture ON fixture.scan_history_id = scan_history.id;
        DELETE candidate_food FROM food candidate_food
        JOIN load_deletable_food_ids fixture ON fixture.food_id = candidate_food.id;
        UPDATE member
        SET scan_count = JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.memberCounters.scanCount'))
        WHERE id = 35;
        SELECT COUNT(*) INTO residual_count
        FROM load_candidate_food_ids candidate
        JOIN food candidate_food ON candidate_food.id = candidate.food_id;
    ELSE
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'target has no state cleanup capability';
    END IF;

    COMMIT;
    SELECT object_cleanup_path FROM load_object_cleanup_paths ORDER BY object_cleanup_path;
    IF residual_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'scan cleanup left generated food with unresolved references';
    END IF;

    DROP TEMPORARY TABLE load_review_ids;
    DROP TEMPORARY TABLE load_order_ids;
    DROP TEMPORARY TABLE load_scan_history_ids;
    DROP TEMPORARY TABLE load_candidate_food_ids;
    DROP TEMPORARY TABLE load_deletable_food_ids;
    DROP TEMPORARY TABLE load_object_cleanup_paths;
END//
DELIMITER ;

CALL kbap_cleanup_load_fixtures();
DROP PROCEDURE kbap_cleanup_load_fixtures;
