docker run --name polynote -e POSTGRES_PASSWORD=polynote -p5432:5432 -d postgres
docker cp db_ddl.sql polynote:db_ddl.sql
sleep 10
docker exec -it polynote psql -U postgres -f db_ddl.sql
