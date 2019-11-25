/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.diff.evaluators;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import software.amazon.smithy.diff.ChangedShape;
import software.amazon.smithy.diff.Differences;
import software.amazon.smithy.model.shapes.EntityShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * A meta-validator that emits a NOTE when an operation resource is added
 * to a service or resource entity.
 *
 * <p>An "AddedOperationBinding" eventId is used when an operation is
 * added, and an "AddedResourceBinding" eventId is used when a
 * resource is added.
 */
public class AddedEntityBinding extends AbstractDiffEvaluator {
    private static final String ADDED_RESOURCE = "AddedResourceBinding";
    private static final String ADDED_OPERATION = "AddedOperationBinding";

    @Override
    public List<ValidationEvent> evaluate(Differences differences) {
        List<ValidationEvent> events = new ArrayList<>();
        differences.changedShapes(EntityShape.class).forEach(change -> validateOperation(change, events));
        return events;
    }

    private void validateOperation(ChangedShape<EntityShape> change, List<ValidationEvent> events) {
        findAdded(change.getOldShape().getOperations(), change.getNewShape().getOperations())
                .forEach(added -> events.add(createAddedEvent(ADDED_OPERATION, change.getNewShape(), added)));

        findAdded(change.getOldShape().getResources(), change.getNewShape().getResources())
                .forEach(added -> events.add(createAddedEvent(ADDED_RESOURCE, change.getNewShape(), added)));
    }

    private Set<ShapeId> findAdded(Set<ShapeId> oldShapes, Set<ShapeId> newShapes) {
        Set<ShapeId> added = new HashSet<>(newShapes);
        added.removeAll(oldShapes);
        return added;
    }

    private ValidationEvent createAddedEvent(String eventId, EntityShape entity, ShapeId addedShape) {
        String descriptor = eventId.equals(ADDED_RESOURCE) ? "Resource" : "Operation";
        String message = String.format(
                "%s binding of `%s` was added to the %s shape, `%s`",
                descriptor, addedShape, entity.getType(), entity.getId());
        return ValidationEvent.builder()
                .eventId(eventId)
                .severity(Severity.NOTE)
                .shape(entity)
                .message(message)
                .build();
    }
}