-- Only draft content may be renumbered; preserve existing group and question order.
CREATE TEMPORARY TABLE draft_question_numbering AS
SELECT q.passage_question_id,
       ROW_NUMBER() OVER (PARTITION BY g.passage_id ORDER BY g.display_order, q.question_number, q.passage_question_id) AS new_number
FROM passage_questions q
JOIN question_groups g ON g.question_group_id = q.question_group_id
JOIN passages p ON p.passage_id = g.passage_id
WHERE p.status = 'DRAFT';

UPDATE passage_questions SET question_number = question_number + 1000000
WHERE passage_question_id IN (SELECT passage_question_id FROM draft_question_numbering);

UPDATE passage_questions SET question_number = (
    SELECT new_number FROM draft_question_numbering d WHERE d.passage_question_id = passage_questions.passage_question_id
)
WHERE passage_question_id IN (SELECT passage_question_id FROM draft_question_numbering);

DROP TABLE draft_question_numbering;
