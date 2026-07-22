package com.example.demo.repositories.mongo;

import com.example.demo.entity.MongoEntity.FieldGroup;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface FieldGroupRepository extends MongoRepository<FieldGroup, String> {

    Optional<FieldGroup> findByName(String name);

    List<FieldGroup> findByIdUser(String idUser);

    boolean existsByName(String name);
}
