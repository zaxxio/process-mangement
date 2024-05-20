# Process Management - Process WatchDog 

# Start 
```shell
docker-compose up -d
```

## API: http://localhost:8080/swagger-ui/index.html

# Stop
```shell
docker-compose down -v
```

# Docker Exec 


Use in the container to check process
```shell
docker exec -it <container_id> bash
ps aux
```

# Screen Shot
![Screenshot](./assets/core-devs-ltd.png)

# Docker Container
![Screenshot](./assets/docker-container-examples.png)
