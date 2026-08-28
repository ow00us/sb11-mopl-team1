\set ON_ERROR_STOP on

SELECT
    jsonb_build_object(
        'index', jsonb_build_object('_index', 'contents', '_id', content.id::text)
    )::text
    || E'\n' ||
    jsonb_build_object(
        'contentId', content.id::text,
        'title', content.title,
        'description', content.description,
        'type', content.type,
        'tags', COALESCE((
            SELECT jsonb_agg(tag.tag ORDER BY tag.tag)
            FROM content_tags tag
            WHERE tag.content_id = content.id
        ), '[]'::jsonb),
        'averageRating', content.average_rating,
        'watcherCount', 0,
        'reviewCount', content.review_count,
        'thumbnailUrl', content.thumbnail_url,
        'createdAt', to_char(
            content.created_at AT TIME ZONE 'UTC',
            'YYYY-MM-DD"T"HH24:MI:SS.MS'
        ),
        'createdAtEpochMicros', FLOOR(EXTRACT(EPOCH FROM content.created_at) * 1000000)::BIGINT
    )::text
FROM contents content
WHERE content.deleted_at IS NULL
ORDER BY content.id;
