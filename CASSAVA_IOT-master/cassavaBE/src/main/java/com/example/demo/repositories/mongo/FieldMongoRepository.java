package com.example.demo.repositories.mongo;

import com.example.demo.entity.MongoEntity.Field;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FieldMongoRepository extends MongoRepository<Field, String> {

    List<Field> findByIdUser(String idUser);
    List<Field> findByGroupId(String groupId);
    boolean existsByName(String name);
}