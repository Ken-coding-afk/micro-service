-- Spring Security 7.0.8 官方 JDBC 模型；只按资源内 PostgreSQL 指引调整列类型。

-- 来源：org/springframework/security/oauth2/server/authorization/client/oauth2-registered-client-schema.sql
/*
IMPORTANT:
    If using PostgreSQL:
        - update ALL columns defined with 'timestamp' to 'timestamptz', to ensure that time instants are stored accurately.
    If using MySQL:
        - add 'preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' to JDBC connection URL
          to ensure that time instants are stored accurately. See https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html
*/
CREATE TABLE oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamptz DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    PRIMARY KEY (id)
);

-- 来源：org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql
/*
IMPORTANT:
    If using PostgreSQL:
        - update ALL columns defined with 'blob' to 'text', as PostgreSQL does not support the 'blob' data type.
        - update ALL columns defined with 'timestamp' to 'timestamptz', to ensure that time instants are stored accurately.
    If using MySQL:
        - add 'preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' to JDBC connection URL
          to ensure that time instants are stored accurately. See https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html
*/
CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes text DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value text DEFAULT NULL,
    authorization_code_issued_at timestamptz DEFAULT NULL,
    authorization_code_expires_at timestamptz DEFAULT NULL,
    authorization_code_metadata text DEFAULT NULL,
    access_token_value text DEFAULT NULL,
    access_token_issued_at timestamptz DEFAULT NULL,
    access_token_expires_at timestamptz DEFAULT NULL,
    access_token_metadata text DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value text DEFAULT NULL,
    oidc_id_token_issued_at timestamptz DEFAULT NULL,
    oidc_id_token_expires_at timestamptz DEFAULT NULL,
    oidc_id_token_metadata text DEFAULT NULL,
    refresh_token_value text DEFAULT NULL,
    refresh_token_issued_at timestamptz DEFAULT NULL,
    refresh_token_expires_at timestamptz DEFAULT NULL,
    refresh_token_metadata text DEFAULT NULL,
    user_code_value text DEFAULT NULL,
    user_code_issued_at timestamptz DEFAULT NULL,
    user_code_expires_at timestamptz DEFAULT NULL,
    user_code_metadata text DEFAULT NULL,
    device_code_value text DEFAULT NULL,
    device_code_issued_at timestamptz DEFAULT NULL,
    device_code_expires_at timestamptz DEFAULT NULL,
    device_code_metadata text DEFAULT NULL,
    PRIMARY KEY (id)
);

-- 来源：org/springframework/security/oauth2/server/authorization/oauth2-authorization-consent-schema.sql
CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);


ALTER TABLE oauth2_registered_client ADD CONSTRAINT oauth2_registered_client_client_id_unique UNIQUE(client_id);
ALTER TABLE oauth2_authorization ADD CONSTRAINT oauth2_authorization_client_fk
    FOREIGN KEY(registered_client_id) REFERENCES oauth2_registered_client(id);
ALTER TABLE oauth2_authorization_consent ADD CONSTRAINT oauth2_consent_client_fk
    FOREIGN KEY(registered_client_id) REFERENCES oauth2_registered_client(id);
CREATE INDEX oauth2_authorization_principal ON oauth2_authorization(registered_client_id,principal_name);

-- 身份控制扩展；协议字段只存官方模型，租户范围为明确集合，不保存通配符。
CREATE TABLE sys_client_policy (
    registered_client_id varchar(100) PRIMARY KEY REFERENCES oauth2_registered_client(id),
    principal_type varchar(16) NOT NULL CHECK (principal_type IN ('user','platform','service')),
    owner_tenant_id uuid REFERENCES sys_tenant(id),
    allowed_tenant_ids uuid[] NOT NULL DEFAULT '{}',
    audiences text[] NOT NULL CHECK (cardinality(audiences)>0),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','DISABLED')),
    version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (array_position(allowed_tenant_ids,NULL) IS NULL AND array_position(audiences,NULL) IS NULL),
    CHECK (owner_tenant_id IS NULL OR allowed_tenant_ids=ARRAY[owner_tenant_id]),
    CHECK (principal_type<>'platform' OR (owner_tenant_id IS NULL AND cardinality(allowed_tenant_ids)=0))
);
CREATE TABLE sys_service_audience (
    audience varchar(160) PRIMARY KEY,
    owner_service varchar(128) NOT NULL UNIQUE,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
    version bigint NOT NULL DEFAULT 0 CHECK(version>=0)
);
REVOKE ALL ON oauth2_registered_client,oauth2_authorization,oauth2_authorization_consent,
    sys_client_policy,sys_service_audience FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON oauth2_registered_client,sys_client_policy,sys_service_audience TO "${runtime_role}";
GRANT SELECT,INSERT,UPDATE,DELETE ON oauth2_authorization,oauth2_authorization_consent TO "${runtime_role}";
