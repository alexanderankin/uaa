--
-- Cloud Foundry
-- Copyright (c) [2015] Pivotal Software, Inc. All Rights Reserved.
--
-- This product is licensed to you under the Apache License, Version 2.0 (the "License").
-- You may not use this product except in compliance with the License.
--
-- This product includes a number of subcomponents with
-- separate copyright notices and license terms. Your use of these
-- subcomponents is subject to the terms and conditions of the
-- subcomponent's license, as noted in the LICENSE file.
--


-- add zone id to the groups table
ALTER TABLE groups ADD COLUMN identity_zone_id varchar(36) DEFAULT 'uaa' NOT NULL ;
ALTER TABLE groups DROP CONSTRAINT unique_uk_2;
CREATE UNIQUE INDEX groups_unique_key ON groups (displayname,identity_zone_id);

-- remove zone id from the group_membership table - it is derived from group_id
DROP INDEX group_membership_unique_key;
ALTER TABLE group_membership DROP COLUMN identity_zone_id;
CREATE UNIQUE INDEX group_membership_unique_key ON group_membership (member_id,group_id);

-- remove zone id from the external_grou_mapping table - it is derived from group_id
DROP INDEX external_group_unique_key;
ALTER TABLE external_group_mapping DROP COLUMN identity_zone_id;
CREATE UNIQUE INDEX external_group_unique_key ON external_group_mapping (origin,external_group,group_id);


-- ALTER TABLE group_membership DROP PRIMARY KEY;
--
-- ALTER TABLE external_group_mapping ADD COLUMN identity_zone_id varchar(36);
-- ALTER TABLE external_group_mapping ADD COLUMN origin varchar(36);
-- ALTER TABLE external_group_mapping DROP PRIMARY KEY;
--
-- UPDATE group_membership SET identity_zone_id = (SELECT identity_zone_id FROM users where users.id = group_membership.member_id);
-- UPDATE group_membership SET identity_zone_id = (SELECT 'uaa' FROM groups where groups.id = group_membership.member_id);
--
-- UPDATE external_group_mapping SET identity_zone_id = 'uaa', origin='ldap';
--
-- CREATE UNIQUE INDEX group_membership_unique_key ON group_membership (identity_zone_id,member_id,group_id);
-- CREATE UNIQUE INDEX external_group_unique_key ON external_group_mapping (identity_zone_id,origin,external_group,group_id);