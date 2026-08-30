DROP PROCEDURE IF EXISTS kbap_cleanup_load_fixtures;

DELIMITER //
CREATE PROCEDURE kbap_cleanup_load_fixtures()
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        DROP TEMPORARY TABLE IF EXISTS load_review_ids;
        RESIGNAL;
    END;

    IF @run_id IS NULL OR @run_id = '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'set a non-empty @run_id before cleanup';
    END IF;
    IF @run_id NOT REGEXP '^[0-9A-Za-z:-]+$' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '@run_id contains unsafe LIKE characters';
    END IF;

    START TRANSACTION;

    CREATE TEMPORARY TABLE load_review_ids (
        review_id BIGINT PRIMARY KEY
    );

    INSERT INTO load_review_ids (review_id)
    SELECT id
    FROM food_review
    WHERE member_id = 35
      AND content LIKE CONCAT('%[load:', @run_id, ']%');

    DELETE FROM report
    WHERE reporter_member_id = 35
      AND detail LIKE CONCAT('%[load:', @run_id, ']%');

    DELETE r
    FROM report r
    JOIN load_review_ids fixture ON fixture.review_id = r.target_id
    WHERE r.target_type = 'REVIEW';

    DELETE child
    FROM review_like child
    JOIN load_review_ids fixture ON fixture.review_id = child.review_id;

    DELETE child
    FROM member_ranking_event child
    JOIN load_review_ids fixture ON fixture.review_id = child.review_id;

    DELETE review
    FROM food_review review
    JOIN load_review_ids fixture ON fixture.review_id = review.id;

    UPDATE member
    SET review_count = (
            SELECT COUNT(*) FROM food_review WHERE member_id = 35 AND status = 'ACTIVE'
        ),
        unique_reviewed_food_count = (
            SELECT COUNT(DISTINCT food_id) FROM food_review WHERE member_id = 35 AND status = 'ACTIVE'
        )
    WHERE id = 35;

    DROP TEMPORARY TABLE load_review_ids;
    COMMIT;
END//
DELIMITER ;

CALL kbap_cleanup_load_fixtures();
DROP PROCEDURE kbap_cleanup_load_fixtures;
