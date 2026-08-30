CREATE TABLE pagination_test (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50),
    email VARCHAR(100),
    status VARCHAR(20)
);

DELIMITER $$
CREATE PROCEDURE InsertDummyData()
BEGIN
    DECLARE i INT DEFAULT 1;
    WHILE i <= 1050 DO
        INSERT INTO pagination_test (name, email, status) 
        VALUES (
            CONCAT('User ', i), 
            CONCAT('user', i, '@example.com'),
            IF(i % 2 = 0, 'Active', 'Pending')
        );
        SET i = i + 1;
    END WHILE;
END$$
DELIMITER ;

CALL InsertDummyData();