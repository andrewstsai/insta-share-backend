package com.andrewstsai.instashare.repository;

import com.andrewstsai.instashare.model.ItemType;
import com.andrewstsai.instashare.model.SessionItem;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessionItemRepository extends CrudRepository<SessionItem, String> {

    List<SessionItem> findBySessionId(String sessionId);

    List<SessionItem> findBySessionIdAndType(String sessionId, ItemType type);

    void deleteBySessionId(String sessionId);
}